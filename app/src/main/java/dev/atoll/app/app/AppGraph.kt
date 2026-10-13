package dev.atoll.app.app

import android.content.Context
import android.content.pm.ApplicationInfo
import dev.atoll.app.BuildConfig
import android.os.Build
import android.os.SystemClock
import dev.atoll.app.core.*
import dev.atoll.app.model.UnreadCount
import dev.atoll.app.model.TimelineSnapshot
import dev.atoll.app.model.TimelineSource
import dev.atoll.app.tasks.ChannelActivityInput
import dev.atoll.app.tasks.ActivityAgentRun
import dev.atoll.app.net.*
import dev.atoll.app.store.*
import dev.atoll.app.terminal.PtyClient
import dev.atoll.app.sync.SyncHub
import dev.atoll.app.ui.screens.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.util.concurrent.atomic.AtomicReference

enum class WorkspacePhase { Booting, Login, Workspace, Incompatible }
data class WorkspaceState(
    val phase: WorkspacePhase = WorkspacePhase.Booting,
    val identityConfirmed: Boolean = false,
    val exporting: Boolean = false,
    val login: LoginViewState = LoginViewState(),
    val navigation: ChannelNavigationViewState = ChannelNavigationViewState(emptyList(), ConnectionPhase.CONNECTING, AccountViewState("")),
    val route: AppRoute? = null,
    val conversation: ConversationViewState? = null,
    val firstOrigin: Origin? = null,
    val channelOpenedAt: Long = 0,
    val notice: String? = null,
    val incompatible: String = "",
    val timeline: TimelineSnapshot? = null,
    val roster: List<ActorRecord> = emptyList(),
    val boot: String = "",
    val newerUnloaded: Boolean = false,
    val rosterCurrent: Boolean = false,
    val timelineSource: TimelineSource? = null,
    val activityInputs: List<ChannelActivityInput> = emptyList(),
    val activityRuns: List<ActivityAgentRun> = emptyList(),
)

/** Feature packages share authentication/transport without reaching into the manager's state. */
data class WorkspaceServices(val server: String, val principalId: String, val selfIds: Map<String, String>,
    val wire: WireClient, val obs: ObsClient, val httpClient: OkHttpClient, val boot: String = "", val pty: PtyClient? = null)

/** Manual app wiring. The manager owns navigation/session facts; channel writers own folds. */
class AppGraph(private val context: Context) {
    val telemetry = AppTelemetry(warningStore=AndroidKeyValueStore(context.getSharedPreferences("diagnostics",Context.MODE_PRIVATE)))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commands = Channel<Event>(256)
    private val mutableState = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = mutableState.asStateFlow()
    private val mutableServices = MutableStateFlow<WorkspaceServices?>(null)
    val services: StateFlow<WorkspaceServices?> = mutableServices.asStateFlow()
    private val preferences = AppPreferences(context)
    private var runtime: Runtime? = null
    private var epoch = 0L
    private val records = linkedMapOf<String, ChannelRecord>()
    private val memberships = mutableMapOf<String, String>()
    private val controllers = mutableMapOf<String, ChannelController>()
    private val channelStates = mutableMapOf<String, ChannelRuntimeState>()
    private val watchers = mutableMapOf<String, Job>()
    private val rosterLoading = mutableMapOf<String, Long>()
    private val rosterGenerations = mutableMapOf<String, Long>()
    private val actorRosters = mutableMapOf<String, List<ActorRecord>>()
    private val accesses = mutableMapOf<String, ChannelAccess>()
    private val backStack = ArrayDeque<AppRoute?>()
    private val focus = AtomicReference("")
    private var requestedRoute: AppRoute? = null
    private var requestedRestoreSurface = false
    private val surfaces = mutableMapOf<String, ChannelSurface>()
    private var route: AppRoute? = null
    private var foreground = true
    private var channelOpenedAt = 0L
    private var notice: String? = null
    private var upgrade: NodeUpdateValue? = null
    private var exporting = false
    private var treeGeneration = 0L
    private var treeJob: Job? = null
    private val networkMonitor = NetworkRecoveryMonitor(context) { post(NetworkAvailable) }

    init {
        telemetry.event("app.boot")
        scope.launch {
            for (event in commands) {
                try { handle(event) }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    telemetry.event("app.command_failed", "error", buildJsonObject { put("exception", failure.javaClass.simpleName) })
                    notice = failure.message ?: "工作区暂时不可用"
                    if (runtime == null) mutableState.value = mutableState.value.copy(phase = WorkspacePhase.Login,
                        login = mutableState.value.login.copy(busy = false, password = "", error = notice))
                    else publish()
                }
            }
        }
        scope.launch { commands.send(Restore) }
    }

    fun login(form: LoginViewState) { post(Login(form)) }
    fun logout() { post(Logout) }
    fun openChannel(id: String) { post(Navigate(AppRoute(id), push = true, restoreSurface = true)) }
    fun channelCreated(id: String, parent: String, name: String, owner: WorkspaceServices, enter: Boolean = false) { post(CreatedChannel(id, parent, name, owner.boot, owner.wire, enter)) }
    fun route(value: String?) { AppRoute.parse(value)?.let { post(Navigate(it, push = true)) } }
    fun tab(value: ChannelTab) { post(Tab(value)) }
    fun files(open: Boolean) { post(Files(open)) }
    fun resources() { post(Resources) }
    fun terminal(open: Boolean) { post(Terminal(open)) }
    fun back() { post(Back) }
    fun follow(value: Boolean) { post(Follow(value)) }
    fun visibleRows(channel: String, keys: Set<String>) { post(VisibleRows(channel, keys)) }
    fun localPlacement(channel: String, owner: WorkspaceServices, echoes: List<dev.atoll.app.model.TimelineEntry>, placed: Set<String>) { post(LocalPlacement(channel, owner.boot, owner.wire, echoes, placed)) }
    fun scopeToggle() { post(ScopeToggle) }
    fun filter(actor: String) { post(Filter(actor)) }
    fun older() { post(Older) }
    fun jumpToBottom() { post(Jump) }
    fun foreground(value: Boolean) { post(Foreground(value)) }
    fun dismissNotice() { post(Notice(null)) }
    fun reconnect() { post(Reconnect) }
    fun upgrade() { post(Upgrade) }
    fun export(performance: Boolean) { post(Export(performance)) }
    fun performanceMark(type: String, elapsedMs: Long, channel: String? = null, source: String? = null) {
        val data = buildJsonObject { put("elapsedMs", elapsedMs); channel?.let { put("channelId", it) }; source?.let { put("source", it) } }
        telemetry.event("perf.$type", data = data)
        android.util.Log.i("AtollPerf", "$type channel=${channel.orEmpty()} source=${source.orEmpty()} elapsed_ms=$elapsedMs")
    }
    fun notice(value: String) { post(Notice(value)) }

    private fun post(event: Event) { scope.launch { commands.send(event) } }

    private suspend fun handle(event: Event) {
        when (event) {
            Restore -> {
                val (server, account) = preferences.account()
                mutableState.value = mutableState.value.copy(login = LoginViewState(server, account))
                preferences.bootstrap(server, account)?.let { cached ->
                    val target = requestedRoute?.let { wanted -> cached.channels.firstOrNull { it.id == wanted.channel || it.qualifiedName == wanted.channel }?.id }
                        ?: cached.lastChannel
                    mutableState.value = mutableState.value.copy(phase = WorkspacePhase.Workspace, navigation = cached.navigation(),
                        route = target?.takeIf(cached.memberships::containsKey)?.let(::AppRoute))
                }
                val debug = readDevelopmentAuth()
                if (debug != null) authenticate(debug.server, debug.account, token = debug.token)
                else if (account.isEmpty()) mutableState.value = mutableState.value.copy(phase = WorkspacePhase.Login)
                else authenticate(server, account)
            }
            is Login -> {
                if (mutableState.value.login.busy) return
                val error = identityFormError(event.form)
                if (error != null) { mutableState.value = mutableState.value.copy(login = event.form.copy(error = error)); return }
                mutableState.value = mutableState.value.copy(login = event.form.copy(busy = true, error = null))
                authenticate(event.form.server, event.form.account, form = event.form)
            }
            is Authenticated -> if (event.epoch == epoch) {
                if (event.failure != null) {
                    if (event.restoring && event.failure is IdentityException && event.failure.status == 401)
                        preferences.forgetBootstrap(mutableState.value.login.server, mutableState.value.login.account)
                    mutableState.value = mutableState.value.copy(phase = WorkspacePhase.Login,
                        login = mutableState.value.login.copy(busy = false, password = "", error = if (event.restoring && event.failure is IdentityException && event.failure.status == 401) null else event.failure.message))
                } else connect(requireNotNull(event.auth))
            }
            is TransportState -> if (event.epoch == epoch) transport(event.state)
            is TransportFrame -> if (event.epoch == epoch) incoming(event.frame)
            is Tree -> if (event.epoch == epoch && event.world == runtime?.world && event.generation == treeGeneration) { event.records.forEach { records[it.id] = it }; publish(); maybeRequestedRoute() }
            is TreeDone -> if (event.epoch == epoch && event.world == runtime?.world && event.generation == treeGeneration) {
                event.records?.let { complete -> records.keys.retainAll(complete.map { it.id }.toSet()); complete.forEach { records[it.id] = it } }
                preferences.put(runtime!!.namespace, "channels", cachedTree(records.values).toString())
                saveBootstrap()
                if (event.failure != null) notice = event.failure
                if (route != null && route!!.channel !in records && route!!.channel !in memberships) { notice = "该频道已关闭"; navigate(null, false) }
                publish(); maybeRequestedRoute()
            }
            is Roster -> if (event.epoch == epoch && event.world == runtime?.world) {
                if (rosterLoading[event.channel] == event.generation) rosterLoading.remove(event.channel)
                if (event.generation == (runtime?.gate?.state?.value as? WireState.Attached)?.generation && event.success) {
                    actorRosters[event.channel] = event.actors
                    rosterGenerations[event.channel] = event.generation
                    controllers[event.channel]?.roster(event.actors)
                }
                publish()
            }
            is Controller -> if (event.epoch == epoch && event.world == runtime?.world) { channelStates[event.id] = event.value; publish() }
            is Navigate -> {
                if (runtime == null || mutableState.value.phase != WorkspacePhase.Workspace || runtime!!.gate.state.value !is WireState.Attached) { requestedRoute = event.route; requestedRestoreSurface = event.restoreSurface }
                else navigate(event.route, event.push, restoreSurface = event.restoreSurface)
            }
            is CreatedChannel -> if (runtime != null && event.owner === mutableServices.value?.wire && event.boot == mutableServices.value?.boot && event.id.isNotEmpty() && event.name.isNotEmpty()) {
                val added = event.id !in records
                records.putIfAbsent(event.id, createdChannelRecord(event.id, event.parent, event.name, records[event.parent]))
                if (added || event.enter) loadTree(epoch)
                if (event.enter) navigate(AppRoute(event.id), true, restoreSurface = true) else publish()
            }
            Back -> if (backStack.isNotEmpty()) navigate(backStack.removeLast(), false) else navigate(null, false)
            is Tab -> route?.let { current ->
                saveSurface(current.channel, (surfaces[current.channel] ?: ChannelSurface.fromRoute(current.tab)).selectTab(event.value == ChannelTab.Tasks))
            }
            is Files -> route?.let { current ->
                saveSurface(current.channel, (surfaces[current.channel] ?: ChannelSurface.fromRoute(current.tab)).files(event.open))
            }
            Resources -> route?.let { current -> saveSurface(current.channel, (surfaces[current.channel] ?: ChannelSurface.fromRoute(current.tab)).resources()) }
            is Terminal -> route?.let { current ->
                saveSurface(current.channel, (surfaces[current.channel] ?: ChannelSurface.fromRoute(current.tab)).terminal(event.open))
            }
            is Export -> {
                val rt = runtime ?: return
                val id = route?.channel
                if (exporting) return
                if (rt.gate.state.value !is WireState.Attached || id == null || id !in memberships) { notice = "请在连接可用的会员频道中导出"; publish(); return }
                exporting = true; notice = null; publish()
                val requestEpoch = epoch; val world = rt.world; val services = mutableServices.value!!
                val context = buildJsonObject { put("server", services.server); put("principalId", rt.principal); put("channelId", id); put("boot", services.boot)
                    put("clientVersion", BuildConfig.VERSION_NAME); put("device", Build.MODEL); put("androidApi", Build.VERSION.SDK_INT) }
                rt.exportJob = rt.scope.launch {
                    try { val file = exportWorkspaceReport(services, id, this@AppGraph.context.cacheDir, event.performance, telemetry, context)
                        commands.send(Exported(requestEpoch, world, file.name, null)) }
                    catch (failure: Exception) { if (failure !is CancellationException) commands.send(Exported(requestEpoch, world, null, failure.message)) }
                }
            }
            is Exported -> if (event.epoch == epoch && event.world == runtime?.world) {
                runtime?.exportJob = null; exporting = false; notice = event.name?.let { "已导出到频道文件：$it" } ?: "导出失败：${event.error}"
                telemetry.event(if (event.error == null) "export.finished" else "export.failed", if (event.error == null) "info" else "error")
                publish()
            }
            Upgrade -> runtime?.update?.start()
            is Update -> if (event.epoch == epoch) { if (upgrade?.phase != event.value.phase) telemetry.event("node_update.phase", data = buildJsonObject { put("phase", event.value.phase) }); upgrade = event.value; publish() }
            Reconnect -> {
                val rt = runtime
                if (rt != null && mutableState.value.phase == WorkspacePhase.Incompatible) {
                    val requestEpoch = ++epoch
                    val server = mutableServices.value!!.server
                    val account = mutableState.value.login.account
                    scope.launch {
                        try { commands.send(Authenticated(requestEpoch, Auth(server, account, rt.identity.session(), rt.identity), null, false)) }
                        catch (failure: Exception) { if (failure !is CancellationException) commands.send(Authenticated(requestEpoch, null, failure, false)) }
                    }
                } else rt?.wire?.reconnectNow()
            }
            is Follow -> controllers[route?.channel]?.follow(event.value && route?.tab == "conversation")
            is VisibleRows -> if (event.channel == route?.channel && route?.tab == "conversation") controllers[event.channel]?.visibleRows(event.keys)
            is LocalPlacement -> if (event.channel == route?.channel && event.owner === mutableServices.value?.wire && event.boot == mutableServices.value?.boot)
                controllers[event.channel]?.localPlacement(event.echoes, event.placed)
            ScopeToggle -> controllers[route?.channel]?.toggleScope()
            is Filter -> controllers[route?.channel]?.filter(event.id)
            Older -> controllers[route?.channel]?.loadOlder()
            Jump -> { controllers[route?.channel]?.jumpToBottom(); channelOpenedAt = SystemClock.elapsedRealtime(); publish() }
            is Foreground -> {
                val resumed = !foreground && event.value
                foreground = event.value
                controllers[route?.channel]?.foreground(event.value)
                if (resumed) runtime?.wire?.reconnectNow()
            }
            NetworkAvailable -> if (mutableState.value.phase == WorkspacePhase.Workspace) {
                telemetry.event("wire.network_available")
                runtime?.wire?.reconnectNow()
            }
            Logout -> {
                val old = runtime
                val currentLogin = mutableState.value.login
                val identity = old?.identity ?: runCatching { IdentityClient(currentLogin.server,
                    AndroidKeyValueStore(context.getSharedPreferences("identity_${accountNamespace(currentLogin.server, currentLogin.account)}", Context.MODE_PRIVATE))) }.getOrNull()
                val login = currentLogin.copy(password = "", busy = identity != null, error = null)
                preferences.forgetBootstrap(login.server, login.account)
                telemetry.switchOwner(null); telemetry.event("identity.logout")
                tearDown()
                mutableState.value = WorkspaceState(phase = WorkspacePhase.Login, login = login)
                if (identity != null) {
                    val requestEpoch = epoch
                    scope.launch {
                        val failure = runCatching { identity.logout() }.exceptionOrNull()
                        identity.cookieJar.clear()
                        commands.send(LoggedOut(requestEpoch, failure?.message))
                    }
                }
            }
            is LoggedOut -> if (event.epoch == epoch) mutableState.value = mutableState.value.copy(login = mutableState.value.login.copy(busy = false,
                error = event.failure?.let { "本机会话已清理，节点退出未确认：$it" }))
            is Expired -> if (event.epoch == epoch && runtime != null) {
                preferences.forgetBootstrap(mutableState.value.login.server, mutableState.value.login.account)
                runtime!!.identity.cookieJar.clear(); telemetry.switchOwner(null); telemetry.event("identity.expired", "warn"); tearDown()
                mutableState.value = WorkspaceState(phase = WorkspacePhase.Login, login = mutableState.value.login.copy(password = "", busy = false, error = "会话已失效，请重新登录"))
            }
            is Observed -> if (event.epoch == epoch && event.world == runtime?.world && event.id == route?.channel) {
                if (event.failure != null) { accesses[event.id] = ChannelAccess.Denied; notice = "无法访问该频道：${event.failure}" }
                else {
                    val rt = runtime!!
                    val attached = rt.gate.state.value as? WireState.Attached ?: return
                    val meta = requireNotNull(event.meta)
                    val receipt = JsonObject(attached.receipt + ("history_meta" to JsonArray(attached.receipt.objects("history_meta").filter { it["channel_id"].string() != event.id } + meta)))
                    rt.gate.connection(attached.copy(receipt = receipt))
                    rt.hub.channel(event.id).retry()
                    accesses[event.id] = ChannelAccess.ObserverActive
                    ensureController(event.id, "", meta.longValue("head_seq"))
                    controllers[event.id]?.follow(route?.tab == "conversation")
                    controllers[event.id]?.open(records[event.id] ?: fallback(event.id))
                    loadRoster(event.id)
                }
                publish()
            }
            is Notice -> { notice = event.value; publish() }
        }
    }

    private fun authenticate(serverValue: String, accountValue: String, form: LoginViewState? = null, token: String? = null) {
        val requestEpoch = ++epoch
        scope.launch {
            try {
                val url = serverValue.trim().toHttpUrl()
                require(url.username.isEmpty() && url.password.isEmpty()) { "服务器地址不能包含账号密码" }
                val server = url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
                val account = accountValue.trim()
                val namespace = accountNamespace(server, account)
                val keyValues = AndroidKeyValueStore(context.getSharedPreferences("identity_$namespace", Context.MODE_PRIVATE))
                val http = OkHttpClient.Builder().apply {
                    if (token != null) addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build()) }
                }.build()
                val identity = IdentityClient(server, keyValues, http)
                val principal = if (form != null) submitIdentity(identity, form) else identity.session()
                commands.send(Authenticated(requestEpoch, Auth(server, account, principal, identity), null, form == null))
            } catch (failure: Exception) {
                if (failure !is CancellationException) commands.send(Authenticated(requestEpoch, null, failure, form == null))
            }
        }
    }

    private suspend fun connect(auth: Auth) {
        tearDown(incrementEpoch = false)
        val requestEpoch = epoch
        val principalId = auth.principal["id"].string()
        require(principalId.isNotEmpty()) { "服务器未返回账号身份" }
        telemetry.switchOwner("${auth.server}\n$principalId"); telemetry.event("identity.confirmed")
        val namespace = accountNamespace(auth.server, principalId)
        preferences.saveAccount(auth.server, auth.account)
        val database = AtollDatabase.open(context, "ledger-$namespace.db")
        val store = RoomRowStore(database)
        val sessionScope = CoroutineScope(SupervisorJob(scope.coroutineContext[Job]) + Dispatchers.Default)
        val http = auth.identity.client.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request()).also { if (it.code == 401) post(Expired(requestEpoch)) }
        }.build()
        val wire = Wire(auth.server, http, Build.MODEL, focus = { focus.get() })
        val gate = GatedWireClient(wire)
        val hub = SyncHub(gate, store, sessionScope)
        val obs = ObsClient(auth.server, http) { post(Expired(requestEpoch)) }
        val pty = PtyClient(auth.server, http)
        runtime = Runtime(namespace, principalId, auth.identity, wire, gate, hub, obs, database, store, sessionScope, pty, name = auth.principal["display_name"].string().ifEmpty { principalId })
        mutableServices.value = WorkspaceServices(auth.server, principalId, emptyMap(), gate, obs, http, pty = pty)
        if (principalId == "root") {
            val update = NodeUpdateMonitor(NodeUpdateClient(auth.server, http), gate, sessionScope, root = true)
            runtime!!.update = update
            sessionScope.launch { update.state.collect { commands.send(Update(requestEpoch, it)) } }
        }
        preferences.get(namespace, "channels")?.let { saved -> runCatching { channelRecords(Json.parseToJsonElement(saved).jsonObject).forEach { records[it.id] = it } } }
        if (requestedRoute == null) {
            requestedRoute = preferences.get(namespace, "last-channel")?.takeIf { it.isNotEmpty() }?.let { AppRoute(it) }
            requestedRestoreSurface = true
        }
        mutableState.value = mutableState.value.copy(phase = WorkspacePhase.Workspace, identityConfirmed = true,
            login = mutableState.value.login.copy(server = auth.server, account = auth.account, password = "", busy = false), notice = null)
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) { wire.state.collect { commands.send(TransportState(requestEpoch, it)) } }
        sessionScope.launch(start = CoroutineStart.UNDISPATCHED) { wire.frames.collect { if (gate.awaitReady(it)) commands.send(TransportFrame(requestEpoch, it)) } }
        preferences.bootstrap(auth.server, auth.account)?.forPrincipal(principalId)?.let { cached ->
            memberships.putAll(cached.memberships)
            for ((id, self) in cached.memberships) ensureController(id, self, cached.heads[id] ?: 0)
            val wanted = requestedRoute
            val channel = wanted?.let { route -> records.values.firstOrNull { it.id == route.channel || it.qualifiedName == route.channel }?.id ?: route.channel }
            if (channel in memberships && wanted != null) { requestedRoute = null; navigate(wanted.copy(channel = channel!!), false, restoreSurface = requestedRestoreSurface) }
        }
        gate.subscribers.first { it > 0 }
        wire.start()
        loadTree(requestEpoch)
        publish()
    }

    private suspend fun transport(state: WireState) {
        telemetry.event("wire.state", data = buildJsonObject { put("state", when (state) { WireState.Connecting -> "connecting"; is WireState.Attached -> "attached"; is WireState.Disconnected -> "disconnected"; WireState.SignedOut -> "signed_out"; is WireState.Incompatible -> "incompatible" }); if (state is WireState.Attached) put("generation", state.generation) })
        val rt = runtime ?: return
        if (state is WireState.Attached) {
            val boot = state.receipt["boot"].string()
            val previous = preferences.get(rt.namespace, "boot")
            if (boot.isNotEmpty() && previous != null && previous != boot) {
                rt.gate.connection(WireState.Connecting)
                rt.exportJob?.cancel(); rt.exportJob = null; exporting = false
                rt.pty.close(); rt.pty = PtyClient(mutableServices.value!!.server, mutableServices.value!!.httpClient)
                stopControllers(); rt.hub.stop(); rt.store.clear()
                preferences.clearWorld(rt.namespace)
                records.clear(); memberships.clear(); channelStates.clear(); actorRosters.clear(); rosterGenerations.clear(); rosterLoading.clear(); accesses.clear(); surfaces.clear()
                route = route?.copy(tab = "conversation", focus = null); backStack.clear()
                rt.world++
                rt.hub = SyncHub(rt.gate, rt.store, rt.scope)
                rt.gate.subscribers.first { it > 0 }
                loadTree(epoch)
            }
            if (boot.isNotEmpty()) preferences.put(rt.namespace, "boot", boot)
            mutableServices.value = mutableServices.value?.copy(boot = boot, pty = rt.pty)
            rt.gate.connection(state)
            applyMemberships(state.receipt)
            route?.channel?.let { loadRoster(it, force = true) }
            maybeRequestedRoute()
            if (route != null && route!!.channel !in memberships) navigate(route, false, force = true)
        } else rt.gate.connection(state)
        if (state is WireState.Incompatible) mutableState.value = mutableState.value.copy(phase = WorkspacePhase.Incompatible, incompatible = state.detail)
        publish()
    }

    private suspend fun incoming(frame: DownFrame) {
        val rt = runtime ?: return
        // The manager's queue preserves attach -> boot check -> feed ordering.
        if (!rt.gate.receive(frame)) return
        when (frame) {
            is DownFrame.Memberships -> { applyMemberships(frame.payload); publish() }
            is DownFrame.Feed -> if (frame.ref.isEmpty()) {
                controllers[frame.row.channelId]?.receive(frame.row)
                val envelope = frame.row.envelope
                if (envelope.status == "processing" || envelope.type.startsWith("system.member.")) loadRoster(frame.row.channelId, envelope.type.startsWith("system.member."))
                if (envelope.type in setOf("system.channel.created", "system.channel.updated", "system.channel.deleted")) loadTree(epoch)
            }
            is DownFrame.ObserveEnded -> {
                accesses[frame.channelId] = if (frame.reason == "channel_retired") ChannelAccess.Denied else ChannelAccess.ObserverStale
                if (frame.reason == "channel_retired" && route?.channel == frame.channelId) { notice = "该频道已关闭"; navigate(null, false) }
                publish()
            }
            else -> Unit
        }
    }

    private suspend fun applyMemberships(payload: JsonObject) {
        val next = payload.objects("memberships").associate { it["channel_id"].string() to it["actor_id"].string() }.filterKeys(String::isNotEmpty)
        val removedCurrent = route?.channel in memberships.keys - next.keys
        for (id in memberships.keys - next.keys) {
            watchers.remove(id)?.cancelAndJoin(); controllers.remove(id)?.stop(); channelStates.remove(id); accesses.remove(id)
        }
        memberships.clear(); memberships.putAll(next)
        // An attach-confirmed membership supersedes any earlier observer/connection result.
        next.keys.forEach { accesses.remove(it) }
        mutableServices.value = mutableServices.value?.copy(selfIds = next.toMap())
        val heads = payload.objects("history_meta").associate { it["channel_id"].string() to it.longValue("head_seq") }
        for ((id, self) in next) ensureController(id, self, heads[id] ?: 0)
        saveBootstrap()
        if (removedCurrent) { notice = "频道成员关系已结束"; navigate(null, false) }
    }

    private suspend fun ensureController(id: String, self: String, head: Long) {
        if (controllers[id]?.selfId == self) return
        watchers.remove(id)?.cancelAndJoin(); controllers.remove(id)?.stop()
        val rt = runtime ?: return
        val controller = ChannelController(id, self, rt.hub.channel(id), rt.store, preferences, rt.namespace, head, rt.scope)
        controllers[id] = controller
        val requestEpoch = epoch
        val world = rt.world
        watchers[id] = rt.scope.launch { controller.state.collect { commands.send(Controller(requestEpoch, world, id, it)) } }
        if (route?.channel == id) { controller.follow(route?.tab == "conversation"); controller.open(records[id] ?: fallback(id)); controller.foreground(foreground); loadRoster(id) }
    }

    private suspend fun navigate(request: AppRoute?, push: Boolean, force: Boolean = false, restoreSurface: Boolean = false) {
        val rt = runtime ?: return
        var target = request?.let { value -> records.values.firstOrNull { it.id == value.channel || it.qualifiedName == value.channel }?.let { value.copy(channel = it.id) } ?: value }
        target?.let { value ->
            val surface = if (restoreSurface) surfaces.getOrPut(value.channel) { ChannelSurface.decode(preferences.get(rt.namespace, "${value.channel}.surface")) }
                else if (route?.channel == value.channel && surfaces[value.channel]?.routeTab == value.tab) surfaces.getValue(value.channel)
                else ChannelSurface.fromRoute(value.tab)
            surfaces[value.channel] = surface
            target = value.copy(tab = surface.routeTab)
        }
        val selected = target
        if (selected == route && !force) { publish(); return }
        if (push) backStack.addLast(route)
        val channelChanged = route?.channel != selected?.channel
        if (channelChanged) controllers[route?.channel]?.closeView()
        route = selected
        focus.set(selected?.channel.orEmpty())
        channelOpenedAt = SystemClock.elapsedRealtime()
        if (selected != null) {
            preferences.put(rt.namespace, "last-channel", selected.channel)
            saveBootstrap()
            if (selected.channel in memberships) {
                controllers[selected.channel]?.follow(selected.tab == "conversation")
                if (channelChanged) controllers[selected.channel]?.open(records[selected.channel] ?: fallback(selected.channel))
                controllers[selected.channel]?.foreground(foreground)
                loadRoster(selected.channel)
            } else {
                accesses[selected.channel] = ChannelAccess.Loading
                val requestEpoch = epoch
                val requestWorld = rt.world
                rt.scope.launch {
                    try {
                        rt.wire.request(Up.OBSERVE, buildJsonObject { put("channel_id", selected.channel) })
                        val attached = rt.wire.state.value as? WireState.Attached ?: error("连接已断开")
                        val meta = rt.wire.request(Up.CHANNEL_META, buildJsonObject { put("channel_id", selected.channel); put("generation", attached.generation) })
                        commands.send(Observed(requestEpoch, requestWorld, selected.channel, meta, null))
                    } catch (failure: Exception) { if (failure !is CancellationException) commands.send(Observed(requestEpoch, requestWorld, selected.channel, null, failure.message)) }
                }
            }
        }
        publish()
    }

    private suspend fun saveBootstrap() {
        val rt = runtime ?: return
        val attached = rt.gate.state.value as? WireState.Attached ?: return
        val services = mutableServices.value ?: return
        val heads = attached.receipt.objects("history_meta").associate { it["channel_id"].string() to it.longValue("head_seq") }
        preferences.saveBootstrap(services.server, mutableState.value.login.account,
            BootstrapCache(rt.principal, rt.name, memberships.toMap(), records.values.toList(), heads, route?.channel))
    }

    private suspend fun saveSurface(channel: String, surface: ChannelSurface) {
        val rt = runtime ?: return
        surfaces[channel] = surface
        preferences.put(rt.namespace, "$channel.surface", surface.encode())
        route?.let { navigate(it.copy(tab = surface.routeTab), false) }
    }

    private suspend fun maybeRequestedRoute() {
        if (runtime?.gate?.state?.value !is WireState.Attached) return
        val requested = requestedRoute ?: return
        val match = records.values.firstOrNull { it.id == requested.channel || it.qualifiedName == requested.channel }
        if (match != null || requested.channel in memberships) { requestedRoute = null; navigate(requested.copy(channel = match?.id ?: requested.channel), false, restoreSurface = requestedRestoreSurface) }
    }

    private fun loadTree(requestEpoch: Long) {
        val rt = runtime ?: return
        val requestWorld = rt.world
        val generation = ++treeGeneration
        treeJob?.cancel()
        treeJob = rt.scope.launch {
            try {
                val semaphore = Semaphore(6)
                suspend fun walk(parent: String): List<ChannelRecord> {
                    val records = semaphore.withPermit { channelRecords(rt.obs.spaceChannels(parent)) }
                    commands.send(Tree(requestEpoch, requestWorld, generation, records))
                    val children = coroutineScope { records.filter { it.type != "actor" }.map { async { walk(it.id) } }.awaitAll().flatten() }
                    return records + children
                }
                val complete = walk("")
                commands.send(TreeDone(requestEpoch, requestWorld, generation, complete, null))
            } catch (failure: Exception) { if (failure !is CancellationException) commands.send(TreeDone(requestEpoch, requestWorld, generation, null, failure.message)) }
        }
    }

    private fun loadRoster(id: String, force: Boolean = false) {
        val rt = runtime ?: return
        val generation = (rt.gate.state.value as? WireState.Attached)?.generation ?: return
        if (!force && rosterGenerations[id] == generation) return
        if (rosterLoading[id] == generation) return
        rosterLoading[id] = generation
        val requestEpoch = epoch
        val requestWorld = rt.world
        rt.scope.launch {
            try { commands.send(Roster(requestEpoch, requestWorld, id, actorRecords(rt.obs.channelActors(id)), generation)) }
            catch (failure: Exception) { if (failure !is CancellationException) commands.send(Roster(requestEpoch, requestWorld, id, emptyList(), generation, success = false)) }
        }
    }

    private fun publish() {
        val rt = runtime ?: return
        val phase = when (rt.gate.state.value) {
            is WireState.Attached -> ConnectionPhase.OPEN
            is WireState.Disconnected -> ConnectionPhase.RECONNECTING
            WireState.SignedOut, is WireState.Incompatible -> ConnectionPhase.CLOSED
            else -> ConnectionPhase.CONNECTING
        }
        val all = records.values + memberships.keys.filter { it !in records }.map(::fallback)
        val channels = sortedChannels(all).map { (record, depth) ->
            val state = channelStates[record.id]
            val member = record.id in memberships
            val access = when {
                accesses[record.id] != null -> accesses.getValue(record.id)
                !member -> ChannelAccess.Discoverable
                state?.error != null -> ChannelAccess.MemberUnavailable
                phase != ConnectionPhase.OPEN -> ChannelAccess.MemberStale
                else -> ChannelAccess.MemberActive
            }
            val unread = if (member) state?.unread ?: UnreadCount(pending = true) else UnreadCount()
            ChannelViewState(record.id, record.qualifiedName.ifEmpty { record.name }, record.description, unread.count,
                state?.activity?.size ?: 0, access = access,
                unreadPhase = if (unread.pending) UnreadPhase.Recovering else if (unread.unknown) UnreadPhase.Unknown else UnreadPhase.Ready,
                activity = state?.activity.orEmpty(), parentId = record.parent, depth = depth)
        }
        val nav = ChannelNavigationViewState(channels, phase, AccountViewState(rt.principal, rt.name, rt.principal == "root"), route?.channel, upgrade = upgrade?.view())
        val channelState = channelStates[route?.channel]
        val currentChannel = channels.firstOrNull { it.id == route?.channel }
        val contentAllowed = currentChannel?.access in setOf(ChannelAccess.MemberActive, ChannelAccess.Available, ChannelAccess.MemberStale, ChannelAccess.ObserverActive, ChannelAccess.ObserverStale)
        val conversation = channelState?.conversation?.takeIf { contentAllowed }?.let { value -> value.copy(channel = currentChannel ?: value.channel, filesOpen = surfaces[route?.channel]?.filesOpen == true, terminalOpen = surfaces[route?.channel]?.terminalOpen == true) }
        mutableState.value = mutableState.value.copy(navigation = nav, route = route, conversation = conversation,
            firstOrigin = channelState?.firstOrigin, channelOpenedAt = channelOpenedAt, notice = notice,
            timeline = channelState?.timeline?.takeIf { contentAllowed }, roster = actorRosters[route?.channel].orEmpty(), boot = mutableServices.value?.boot.orEmpty(), exporting = exporting,
            newerUnloaded = channelState?.newerUnloaded == true,
            timelineSource = channelState?.timelineSource?.takeIf { contentAllowed },
            activityInputs = visibleActivityChannels(channels,memberships.keys).map { ChannelActivityInput(it.id, it.name, channelStates[it.id]?.taskFacts.orEmpty()) },
            activityRuns = visibleActivityChannels(channels,memberships.keys).flatMap { channelStates[it.id]?.agentRuns.orEmpty() },
            rosterCurrent = (rt.gate.state.value as? WireState.Attached)?.generation?.let { rosterGenerations[route?.channel] == it } == true)
    }

    private suspend fun stopControllers() {
        watchers.values.forEach { it.cancel() }; watchers.values.forEach { it.join() }; watchers.clear()
        controllers.values.forEach { it.stop() }; controllers.clear()
    }
    private suspend fun tearDown(incrementEpoch: Boolean = true) {
        if (incrementEpoch) epoch++
        runtime?.let { rt -> rt.update?.close(); rt.pty.close(); stopControllers(); rt.hub.stop(); rt.scope.cancel(); rt.wire.close(); withContext(Dispatchers.IO) { rt.database.close() } }
        runtime = null
        mutableServices.value = null
        records.clear(); memberships.clear(); channelStates.clear(); actorRosters.clear(); rosterGenerations.clear(); accesses.clear(); surfaces.clear(); rosterLoading.clear(); route = null; backStack.clear(); notice = null; upgrade = null; exporting = false
    }
    private fun fallback(id: String) = ChannelRecord(id, id, null, id)
    private fun cachedTree(records: Collection<ChannelRecord>) = buildJsonObject {
        put("items", buildJsonArray { records.forEach { value -> add(buildJsonObject { put("declared", buildJsonObject {
            put("id", value.id); put("name", value.name); put("qualified_name", value.qualifiedName); put("type", value.type)
            value.parent?.let { put("parent_id", it) }; put("description", value.description)
        }) }) } })
    }

    /** Test-only bootstrap; release builds never read external credentials. Deleted before use. */
    private suspend fun readDevelopmentAuth(): DevelopmentAuth? = withContext(Dispatchers.IO) {
        val directory = when {
            BuildConfig.DEV_AUTH_EXTERNAL -> context.getExternalFilesDir(null)
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 -> context.filesDir
            else -> null
        } ?: return@withContext null
        val file = File(directory, "integration-auth.json")
        if (!file.isFile) return@withContext null
        val text = try { file.readText() } finally { file.delete() }
        runCatching { val value = Json.parseToJsonElement(text).jsonObject; DevelopmentAuth(value["server"].string(), value["account"].string(), value["token"].string()) }.getOrNull()
    }

    private data class DevelopmentAuth(val server: String, val account: String, val token: String)
    private data class Auth(val server: String, val account: String, val principal: JsonObject, val identity: IdentityClient)
    private class Runtime(val namespace: String, val principal: String, val identity: IdentityClient, val wire: Wire,
        val gate: GatedWireClient, var hub: SyncHub, val obs: ObsClient, val database: AtollDatabase, val store: RowStore, val scope: CoroutineScope, var pty: PtyClient, var world: Long = 0, val name: String = principal, var update: NodeUpdateMonitor? = null, var exportJob: Job? = null)
    private sealed interface Event
    private data object Restore : Event
    private data class Login(val form: LoginViewState) : Event
    private data class Authenticated(val epoch: Long, val auth: Auth?, val failure: Exception?, val restoring: Boolean) : Event
    private data class TransportState(val epoch: Long, val state: WireState) : Event
    private data class TransportFrame(val epoch: Long, val frame: DownFrame) : Event
    private data class Tree(val epoch: Long, val world: Long, val generation: Long, val records: List<ChannelRecord>) : Event
    private data class TreeDone(val epoch: Long, val world: Long, val generation: Long, val records: List<ChannelRecord>?, val failure: String?) : Event
    private data class CreatedChannel(val id: String, val parent: String, val name: String, val boot: String, val owner: WireClient, val enter: Boolean) : Event
    private data class Roster(val epoch: Long, val world: Long, val channel: String, val actors: List<ActorRecord>, val generation: Long, val success: Boolean = true) : Event
    private data class Controller(val epoch: Long, val world: Long, val id: String, val value: ChannelRuntimeState) : Event
    private data class Navigate(val route: AppRoute, val push: Boolean, val restoreSurface: Boolean = false) : Event
    private data object Back : Event
    private data class Tab(val value: ChannelTab) : Event
    private data class Files(val open: Boolean) : Event
    private data object Resources : Event
    private data class Terminal(val open: Boolean) : Event
    private data object Reconnect : Event
    private data object Upgrade : Event
    private data class Export(val performance: Boolean) : Event
    private data class Exported(val epoch: Long, val world: Long, val name: String?, val error: String?) : Event
    private data class Update(val epoch: Long, val value: NodeUpdateValue) : Event
    private data class Follow(val value: Boolean) : Event
    private data class VisibleRows(val channel: String, val keys: Set<String>) : Event
    private data class LocalPlacement(val channel: String, val boot: String, val owner: WireClient, val echoes: List<dev.atoll.app.model.TimelineEntry>, val placed: Set<String>) : Event
    private data object ScopeToggle : Event
    private data class Filter(val id: String?) : Event
    private data object Older : Event
    private data object Jump : Event
    private data class Foreground(val value: Boolean) : Event
    private data object NetworkAvailable : Event
    private data object Logout : Event
    private data class LoggedOut(val epoch: Long, val failure: String?) : Event
    private data class Expired(val epoch: Long) : Event
    private data class Observed(val epoch: Long, val world: Long, val id: String, val meta: JsonObject?, val failure: String?) : Event
    private data class Notice(val value: String?) : Event
}

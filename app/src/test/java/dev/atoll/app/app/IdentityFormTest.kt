package dev.atoll.app.app

import dev.atoll.app.net.*
import dev.atoll.app.ui.screens.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class IdentityFormTest {
    @Test fun registrationCreatesTheSessionWithAnOptionalNameAndShortPasswordNeverReachesHttp() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val identity = IdentityClient(server.url("/").toString(), MemoryKeyValueStore())
            val form = LoginViewState(server.url("/").toString(), " user@example.com ", "test-pass", mode = AuthMode.Register, displayName = " User ")
            server.enqueue(MockResponse().setBody("""{"id":"user"}""").addHeader("Set-Cookie", "session=test-session; Path=/; HttpOnly"))
            assertEquals(JsonPrimitive("user"), submitIdentity(identity, form)["id"])
            val request = server.takeRequest()
            assertEquals("/api/identity/register", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("user@example.com"), body["email"])
            assertEquals(JsonPrimitive("User"), body["display_name"])
            assertEquals(1, identity.cookieJar.loadForRequest(server.url("/")).size)
            try { submitIdentity(identity, form.copy(password = "short")); fail("short registration password") } catch (_: IllegalArgumentException) { }
            assertEquals(1, server.requestCount)
            assertNull(identityFormError(form.copy(mode = AuthMode.Login, account = "root", password = "short")))
        }
    }
}

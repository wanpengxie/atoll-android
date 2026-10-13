package dev.atoll.app.net

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test

class IdentityClientTest {
    @Test fun loginPersistsScopedCookieAndLogoutClearsIt() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val store = MemoryKeyValueStore()
            val identity = IdentityClient(server.url("/").toString(), store)
            server.enqueue(MockResponse().setBody("""{"id":"root"}""").addHeader("Set-Cookie", "session=opaque; Path=/; HttpOnly"))
            identity.login("root", "secret")
            val login = server.takeRequest()
            assertEquals("/api/identity/login", login.path)
            assertEquals(JsonPrimitive("root"), Json.parseToJsonElement(login.body.readUtf8()).jsonObject["email"])
            assertTrue(identity.cookieJar.loadForRequest(server.url("/")).single().httpOnly)
            // Build a new client over the same preferences: session cookie survives.
            val restarted = IdentityClient(server.url("/").toString(), store)
            server.enqueue(MockResponse().setBody("{}"))
            restarted.session()
            assertEquals("session=opaque", server.takeRequest().getHeader("Cookie"))
            assertTrue(restarted.cookieJar.loadForRequest(HttpUrl.Builder().scheme("http").host("example.com").build()).isEmpty())
            server.enqueue(MockResponse().setBody("{}"))
            restarted.logout()
            assertEquals("POST", server.takeRequest().method)
            assertTrue(restarted.cookieJar.loadForRequest(server.url("/")).isEmpty())
            assertTrue(PersistentCookieJar(store).loadForRequest(server.url("/")).isEmpty())
        }
    }
    @Test fun registerUsesOptionalFieldsAndErrorsPreserveCodeAndDetail() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val identity = IdentityClient(server.url("/").toString(), MemoryKeyValueStore())
            server.enqueue(MockResponse().setBody("{}"))
            identity.register("a@b", "secret", "a", "A")
            val request = server.takeRequest()
            assertEquals("/api/identity/register", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(JsonPrimitive("A"), body["display_name"])
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"invalid_credentials","detail":"wrong password"}"""))
            try { identity.login("a@b", "wrong"); fail("expected IdentityException") }
            catch (e: IdentityException) {
                assertEquals(401, e.status); assertEquals("invalid_credentials", e.code); assertEquals("wrong password", e.detail)
            }
        }
    }
    @Test fun expiredCookiesAreRemovedAndPathAndSecureAreRespected() {
        val jar = PersistentCookieJar(MemoryKeyValueStore())
        val url = HttpUrl.Builder().scheme("https").host("example.com").build()
        jar.saveFromResponse(url, listOf(
            Cookie.Builder().name("secure").value("v").hostOnlyDomain("example.com").path("/private").secure().build(),
            Cookie.Builder().name("old").value("v").hostOnlyDomain("example.com").expiresAt(1).build(),
        ))
        assertTrue(jar.loadForRequest(url).isEmpty())
        assertEquals(1, jar.loadForRequest(url.resolve("/private/page")!!).size)
        assertTrue(jar.loadForRequest(url.newBuilder().scheme("http").encodedPath("/private/page").build()).isEmpty())
        assertTrue(jar.loadForRequest(url.newBuilder().host("sub.example.com").encodedPath("/private/page").build()).isEmpty())
    }
}

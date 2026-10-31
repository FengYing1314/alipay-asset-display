package io.github.fengying.alipaydisplay;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class WebDocumentStartProbePolicyTest {
    public interface Provider {
        List<Object> getDocumentStartScripts();
    }

    @Test
    public void unauthorizedGetterPreservesIdentityAndCallsHostOnce() {
        AtomicInteger calls = new AtomicInteger();
        List<Object> original = Collections.singletonList(new Object());
        Provider host =
                () -> {
                    calls.incrementAndGet();
                    return original;
                };
        Provider proxy =
                (Provider) WebDocumentStartProbe.Policy.compose(host, Provider.class, () -> null);
        assertSame(original, proxy.getDocumentStartScripts());
        assertEquals(1, calls.get());
    }

    @Test
    public void authorizedGetterAppendsWithoutChangingHostListOrOrder() {
        Object first = new Object(), second = new Object(), addition = new Object();
        List<Object> original = Collections.unmodifiableList(Arrays.asList(first, second));
        Provider proxy =
                (Provider)
                        WebDocumentStartProbe.Policy.compose(
                                (Provider) () -> original, Provider.class, () -> addition);
        List<Object> combined = proxy.getDocumentStartScripts();
        assertEquals(Arrays.asList(first, second, addition), combined);
        assertEquals(Arrays.asList(first, second), original);
        assertNotSame(original, combined);
    }

    @Test
    public void hostExceptionIsUnwrappedAndAdditionIsNeverCalled() {
        IllegalStateException hostError = new IllegalStateException("fixed test failure");
        AtomicInteger additions = new AtomicInteger();
        Provider proxy =
                (Provider)
                        WebDocumentStartProbe.Policy.compose(
                                (Provider)
                                        () -> {
                                            throw hostError;
                                        },
                                Provider.class,
                                () -> {
                                    additions.incrementAndGet();
                                    return new Object();
                                });
        try {
            proxy.getDocumentStartScripts();
            fail();
        } catch (IllegalStateException actual) {
            assertSame(hostError, actual);
        }
        assertEquals(0, additions.get());
    }

    @Test
    public void failedProbePreservesOriginalNullAndListResults() {
        List<Object> original = Collections.singletonList(new Object());
        Provider proxy =
                (Provider)
                        WebDocumentStartProbe.Policy.compose(
                                (Provider) () -> original,
                                Provider.class,
                                () -> {
                                    throw new IllegalArgumentException();
                                });
        assertSame(original, proxy.getDocumentStartScripts());
        Provider nullProvider =
                (Provider)
                        WebDocumentStartProbe.Policy.compose(
                                (Provider) () -> null, Provider.class, () -> null);
        assertNull(nullProvider.getDocumentStartScripts());
    }

    @Test
    public void routeAndOriginRejectWildcardsCredentialsAndNonHttps() {
        assertEquals(
                "https://example.invalid/p",
                WebDocumentStartProbe.Policy.route(
                        "https://EXAMPLE.invalid:443/p?q=private#fragment"));
        assertEquals(
                "https://example.invalid:8443",
                WebDocumentStartProbe.Policy.origin("https://example.invalid:8443/p?q=private"));
        for (String value :
                new String[] {
                    "http://example.invalid/p",
                    "https://name@example.invalid/p",
                    "https://*.invalid/p",
                    "javascript:void(0)",
                    "https://example.invalid:99999/p"
                }) {
            assertNull(WebDocumentStartProbe.Policy.route(value));
            assertNull(WebDocumentStartProbe.Policy.origin(value));
        }
        assertEquals(-1, WebDocumentStartProbe.Policy.count(1.0));
        assertEquals(-1, WebDocumentStartProbe.Policy.count(-1));
        assertEquals(-1, WebDocumentStartProbe.Policy.count(10001));
        assertEquals(10000, WebDocumentStartProbe.Policy.count(10000));
    }

    @Test
    public void ownerChangesInvalidateNavigationAndUnknownIdentityNeverMatches() {
        Object token = new Object();
        WebDocumentStartProbe.Owner first =
                new WebDocumentStartProbe.Owner(
                        token, "77700300", "1.2.3", "https://example.invalid/p?q=one", 212370);
        assertTrue(
                first.same(
                        new WebDocumentStartProbe.Owner(
                                token,
                                "77700300",
                                "1.2.3",
                                "https://example.invalid/p?q=one",
                                212370)));
        assertFalse(
                first.same(
                        new WebDocumentStartProbe.Owner(
                                token,
                                "77700300",
                                "1.2.3",
                                "https://example.invalid/p?q=two",
                                212370)));
        assertFalse(
                first.same(
                        new WebDocumentStartProbe.Owner(
                                new Object(),
                                "77700300",
                                "1.2.3",
                                "https://example.invalid/p?q=one",
                                212370)));
        assertNull(WebDocumentStartProbe.Policy.profile(first, "https://example.invalid/p"));
        assertNull(WebDocumentStartProbe.Policy.profile(null, "https://example.invalid/p"));
    }

    @Test
    public void scriptOnlyProbesAndDoesNotReadAmountTextOrWriteDom() {
        String script =
                WebDocumentStartProbe.Policy.script(
                        "0123456789abcdef", "https://example.invalid/KEY/NONCE/PROFILE", "fund");
        for (String forbidden :
                new String[] {
                    "textContent",
                    "nodeValue",
                    "innerHTML",
                    "appendChild",
                    "setAttribute",
                    "fetch(",
                    "XMLHttpRequest",
                    "console.",
                    "localStorage",
                    "sessionStorage",
                    "document.cookie",
                    "toDataURL",
                    "getImageData"
                }) {
            assertFalse(forbidden, script.contains(forbidden));
        }
        assertTrue(script.contains("window.top!==window"));
        assertTrue(script.contains("href===location.href"));
        assertTrue(script.contains("MutationObserver"));
        assertTrue(script.contains("first-contentful-paint"));
        assertTrue(script.contains("old.close()"));
        assertTrue(script.contains("https://example.invalid/KEY/NONCE/PROFILE"));
        assertFalse(script.contains("?q="));
    }

    @Test
    public void pendingNavigationWaitsForProofButExpiresAtTenSeconds() {
        WebDocumentStartProbe.Policy.PendingNavigations<Object> pending =
                new WebDocumentStartProbe.Policy.PendingNavigations<>();
        Object view = new Object();
        WebDocumentStartProbe.Policy.PendingNavigation request =
                pending.offer(view, "https://example.invalid/p", 100L);
        assertSame(request, pending.get(view, 10099L));
        assertEquals(1L, pending.nextDelay(10099L));
        assertNull(WebDocumentStartProbe.Policy.profile(null, request.requestedUrl));
        assertNull(pending.get(view, 10100L));
        assertFalse(pending.take(view, request, 10100L));
        assertEquals(1, pending.expired);
        assertEquals(-1L, pending.nextDelay(10100L));
    }

    @Test
    public void pendingCapacityIsEightAndNewNavigationReplacesOnlyItsGeneration() {
        WebDocumentStartProbe.Policy.PendingNavigations<Object> pending =
                new WebDocumentStartProbe.Policy.PendingNavigations<>();
        List<Object> views = new ArrayList<>();
        for (int index = 0; index < 9; index++) views.add(new Object());
        WebDocumentStartProbe.Policy.PendingNavigation first = null;
        for (int index = 0; index < 8; index++) {
            WebDocumentStartProbe.Policy.PendingNavigation value =
                    pending.offer(views.get(index), "https://example.invalid/p", 0L);
            assertNotNull(value);
            if (index == 0) first = value;
        }
        assertNull(pending.offer(views.get(8), "https://example.invalid/p", 0L));
        assertEquals(8, pending.size(0L));
        WebDocumentStartProbe.Policy.PendingNavigation replacement =
                pending.offer(views.get(0), "https://example.invalid/p", 1L);
        assertNotNull(replacement);
        assertNotEquals(first.generation, replacement.generation);
        assertFalse(pending.take(views.get(0), first, 1L));
        assertSame(replacement, pending.get(views.get(0), 1L));
        assertTrue(pending.take(views.get(0), replacement, 1L));
        assertFalse(pending.take(views.get(0), replacement, 1L));
    }

    @Test
    public void retireCloseAndClockRollbackCannotRevivePendingRequests() {
        WebDocumentStartProbe.Policy.PendingNavigations<Object> pending =
                new WebDocumentStartProbe.Policy.PendingNavigations<>();
        Object view = new Object();
        WebDocumentStartProbe.Policy.PendingNavigation first =
                pending.offer(view, "https://example.invalid/p?q=one", 500L);
        pending.remove(view);
        assertFalse(pending.take(view, first, 501L));
        WebDocumentStartProbe.Policy.PendingNavigation second =
                pending.offer(view, "https://example.invalid/p?q=two", 501L);
        assertFalse(pending.take(view, first, 501L));
        assertNull(pending.get(view, 500L));
        assertFalse(pending.take(view, second, 501L));
        WebDocumentStartProbe.Policy.PendingNavigation third =
                pending.offer(view, "https://example.invalid/p?q=three", 600L);
        pending.clear();
        assertFalse(pending.take(view, third, 601L));
        assertEquals(0, pending.size(601L));
    }

    @Test
    public void expiredSlotsAreReclaimedAndUnknownRoutesAreNeverAdmittedByPolicy() {
        WebDocumentStartProbe.Policy.PendingNavigations<Object> pending =
                new WebDocumentStartProbe.Policy.PendingNavigations<>();
        List<Object> views = new ArrayList<>();
        for (int index = 0; index < 9; index++) views.add(new Object());
        for (int index = 0; index < 8; index++)
            pending.offer(views.get(index), "https://example.invalid/p", 0L);
        assertNotNull(pending.offer(views.get(8), "https://example.invalid/p", 10000L));
        assertEquals(8, pending.expired);
        assertEquals(1, pending.size(10000L));
        assertFalse(WebDocumentStartProbe.Policy.knownRoute("https://example.invalid/p"));
        assertFalse(WebDocumentStartProbe.Policy.knownRoute("http://example.invalid/p"));
        assertFalse(WebDocumentStartProbe.Policy.knownRoute(null));
    }

    @Test
    public void navigationDuringHostGetterCannotBorrowTheNewGeneration() {
        AtomicInteger generation = new AtomicInteger(), calls = new AtomicInteger();
        List<Object> original = Collections.singletonList(new Object());
        Provider host =
                () -> {
                    calls.incrementAndGet();
                    generation.incrementAndGet();
                    return original;
                };
        Provider proxy =
                (Provider)
                        WebDocumentStartProbe.Policy.composePrepared(
                                host,
                                Provider.class,
                                () -> {
                                    int expected = generation.get();
                                    return () -> generation.get() == expected ? new Object() : null;
                                });
        assertSame(original, proxy.getDocumentStartScripts());
        assertEquals(1, calls.get());
    }

    @Test
    public void failedPrivatePreparationStillCallsTheHostExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        List<Object> original = Collections.singletonList(new Object());
        Provider host =
                () -> {
                    calls.incrementAndGet();
                    return original;
                };
        Provider proxy =
                (Provider)
                        WebDocumentStartProbe.Policy.composePrepared(
                                host,
                                Provider.class,
                                () -> {
                                    throw new IllegalArgumentException();
                                });
        assertSame(original, proxy.getDocumentStartScripts());
        assertEquals(1, calls.get());
    }
}

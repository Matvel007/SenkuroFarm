package com.example.senkurofarm

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ExistingCardCollectorTest {
    @Test
    fun cardPresentBeforeCollectorStartsIsCollected() {
        runCollectorScenario(
            cardPresentBeforeStart = true,
            collectorScript = existingCardCollectorScript(),
            bridgeName = "SenkuroFarm"
        )
    }

    @Test
    fun cardAppearingAfterCollectorStartsIsCollected() {
        runCollectorScenario(
            cardPresentBeforeStart = false,
            collectorScript = existingCardCollectorScript(),
            bridgeName = "SenkuroFarm"
        )
    }

    @Test
    fun backgroundCollectorHandlesCardPresentBeforeStart() {
        runCollectorScenario(
            cardPresentBeforeStart = true,
            collectorScript = FarmService().backgroundCardCollectorScript(),
            bridgeName = "SenkuroFarmNative"
        )
    }

    @Test
    fun backgroundCollectorHandlesCardAppearingDuringFarm() {
        runCollectorScenario(
            cardPresentBeforeStart = false,
            collectorScript = FarmService().backgroundCardCollectorScript(),
            bridgeName = "SenkuroFarmNative"
        )
    }

    private fun runCollectorScenario(
        cardPresentBeforeStart: Boolean,
        collectorScript: String,
        bridgeName: String
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pageLoaded = CountDownLatch(1)
        val collected = CountDownLatch(1)
        val result = arrayOf("", "")
        var webView: WebView? = null

        instrumentation.runOnMainSync {
            webView = WebView(context).apply {
                settings.javaScriptEnabled = true
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun onCardCollected(name: String?, rank: String?) {
                            result[0] = name.orEmpty()
                            result[1] = rank.orEmpty()
                            collected.countDown()
                        }
                    },
                    bridgeName
                )
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        pageLoaded.countDown()
                    }
                }
                loadDataWithBaseURL(
                    "https://senkuro.me/",
                    testPage(cardPresentBeforeStart),
                    "text/html",
                    "UTF-8",
                    null
                )
            }
        }

        assertTrue("Тестовая страница не загрузилась", pageLoaded.await(10, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            webView?.evaluateJavascript(collectorScript, null)
            if (!cardPresentBeforeStart) {
                webView?.evaluateJavascript("spawnCardDrop();", null)
            }
        }
        assertTrue("Карточка не была собрана", collected.await(10, TimeUnit.SECONDS))
        assertEquals("Тестовая карта", result[0])
        assertEquals("S", result[1])

        instrumentation.runOnMainSync {
            webView?.evaluateJavascript(
                """
                    window.__senkuroVisibleCollectorDispose &&
                      window.__senkuroVisibleCollectorDispose();
                    window.__senkuroFarmDispose && window.__senkuroFarmDispose();
                """.trimIndent(),
                null
            )
            webView?.destroy()
        }
    }

    private companion object {
        fun testPage(cardPresentBeforeStart: Boolean) = """
            <!doctype html>
            <html>
              <body>
                <script>
                  function spawnCardDrop() {
                    const drop = document.createElement('button');
                    drop.innerHTML = '<img class="cards-drop" alt="Выпавшая карта">';
                    drop.addEventListener('click', function() {
                      this.remove();
                      const modal = document.createElement('div');
                      modal.className = 'modal-container-drop';
                      modal.innerHTML =
                        '<a class="collectible-card" href="/cards/test-s">' +
                        '<div class="collectible-card__front">' +
                        '<img alt="Тестовая карта"></div></a>';
                      modal.addEventListener('click', function() { modal.remove(); });
                      document.body.appendChild(modal);
                    });
                    document.body.appendChild(drop);
                  }
                  ${if (cardPresentBeforeStart) "spawnCardDrop();" else ""}
                </script>
              </body>
            </html>
        """.trimIndent()
    }
}

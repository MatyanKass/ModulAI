package com.modulai.core.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTest {
    @Test fun stripsMarkupAndScripts() {
        val html = """
            <html><head><title>Кошки &amp; собаки</title><style>p{}</style></head>
            <body><nav>Menu</nav><script>var a = "<p>no</p>";</script>
            <h1>Заголовок</h1><p>Первый&nbsp;абзац &mdash; текст.</p><!-- hidden -->
            <div>Второй <b>абзац</b> &#128512; &#x41;</div></body></html>
        """
        assertEquals("Кошки & собаки", Html.title(html))
        assertEquals("Заголовок\nПервый абзац — текст.\nВторой абзац 😀 A", Html.toText(html))
    }

    @Test fun parsesDuckDuckGoResults() {
        val html = """
            <div class="result"><h2 class="result__title">
              <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1&amp;rut=abc">First <b>hit</b></a></h2>
              <a class="result__snippet" href="//duckduckgo.com/l/?uddg=x">Snippet <b>one</b> &amp; more</a></div>
            <div class="result result--ad"><a class="result__a" href="https://duckduckgo.com/y.js?ad=1">Ad</a></div>
            <div class="result"><a class="result__a" href="https://example.org/b">Second</a></div>
        """
        assertEquals(
            listOf(
                SearchResult("First hit", "https://example.com/a?x=1", "Snippet one & more"),
                SearchResult("Second", "https://example.org/b", ""),
            ),
            Html.parseDuckDuckGo(html),
        )
    }

    @Test fun parsesLiteResults() {
        val html = """
            <tr><td><a rel="nofollow" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2F" class='result-link'>Title</a></td></tr>
            <tr><td class='result-snippet'>Some text</td></tr>
        """
        assertEquals(listOf(SearchResult("Title", "https://example.com/", "Some text")), Html.parseDuckDuckGoLite(html))
    }

    @Test fun emptyPageGivesNoResults() {
        assertEquals(emptyList<SearchResult>(), Html.parseDuckDuckGo("<html>challenge</html>"))
    }
}

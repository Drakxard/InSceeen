"use strict";

(() => {
  const content = document.getElementById("content");
  let bridge = null;
  let lastHeight = -1;
  let renderGeneration = 0;
  let scrollRevision = 0;

  function reportHeight() {
    const rect = content.getBoundingClientRect();
    const height = Math.max(1, Math.ceil(rect.bottom - rect.top));
    if (height !== lastHeight) {
      lastHeight = height;
      if (bridge && bridge.reportHeight) {
        bridge.reportHeight(height);
      }
    }
    return height;
  }

  function isAtBottom() {
    return window.scrollY + window.innerHeight >= document.documentElement.scrollHeight - 16;
  }

  window.inscreenSetFontSize = (pixels) => {
    const safeSize = Math.max(10, Math.min(72, Number(pixels) || 18));
    document.documentElement.style.setProperty("--inscreen-font-size", `${safeSize}px`);
    requestAnimationFrame(reportHeight);
  };

  window.inscreenSetContent = (text, followBottom) => {
    const generation = ++renderGeneration;
    const shouldFollow = Boolean(followBottom) || isAtBottom();
    const previousTop = window.scrollY;
    const previousScrollRevision = scrollRevision;

    // La respuesta siempre entra como texto. KaTeX solo transforma los nodos
    // matemáticos que encuentra después, sin permitir HTML de la respuesta.
    content.textContent = String(text ?? "");
    try {
      renderMathInElement(content, {
        delimiters: [
          {left: "\\[", right: "\\]", display: true},
          {left: "\\(", right: "\\)", display: false}
        ],
        throwOnError: false,
        strict: "ignore",
        trust: false
      });
    } catch (error) {
      console.warn("InScreen: KaTeX no pudo completar el render", error);
    }

    requestAnimationFrame(() => requestAnimationFrame(() => {
      if (generation !== renderGeneration) {
        return;
      }
      reportHeight();
      if (scrollRevision !== previousScrollRevision) {
        return;
      }
      if (shouldFollow) {
        window.scrollTo(0, document.documentElement.scrollHeight);
      } else {
        const maximum = Math.max(0, document.documentElement.scrollHeight - window.innerHeight);
        window.scrollTo(0, Math.min(previousTop, maximum));
      }
    }));
  };

  window.inscreenScrollBy = (delta) => window.scrollBy(0, Number(delta) || 0);
  window.inscreenMetrics = () => ({
    scrollTop: window.scrollY,
    scrollHeight: document.documentElement.scrollHeight,
    clientHeight: window.innerHeight,
    contentHeight: reportHeight(),
    katexNodes: content.querySelectorAll(".katex").length,
    text: content.innerText
  });

  new QWebChannel(qt.webChannelTransport, (channel) => {
    bridge = channel.objects.bridge;
    reportHeight();
  });

  new ResizeObserver(reportHeight).observe(content);
  window.addEventListener("scroll", () => { scrollRevision += 1; }, {passive: true});
  window.addEventListener("resize", reportHeight, {passive: true});
  window.inscreenReady = true;
  reportHeight();
})();

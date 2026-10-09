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

  function closingMarker(text, marker, start) {
    let position = start;
    while (position < text.length) {
      position = text.indexOf(marker, position);
      if (position < 0) return -1;
      let slashes = 0;
      for (let index = position - 1; index >= 0 && text[index] === "\\"; index -= 1) {
        slashes += 1;
      }
      if (slashes % 2 === 0) return position;
      position += marker.length;
    }
    return -1;
  }

  function appendFormattedText(parent, value) {
    const text = String(value ?? "");
    let plain = "";
    const flush = () => {
      if (plain) {
        parent.appendChild(document.createTextNode(plain));
        plain = "";
      }
    };

    for (let index = 0; index < text.length;) {
      const mathClose = text.startsWith("\\(", index) ? "\\)" :
        (text.startsWith("\\[", index) ? "\\]" : null);
      if (mathClose) {
        const end = text.indexOf(mathClose, index + 2);
        if (end >= 0) {
          plain += text.slice(index, end + 2);
          index = end + 2;
          continue;
        }
      }
      if (text[index] === "\\" && text[index + 1] === "*") {
        plain += "*";
        index += 2;
        continue;
      }
      const marker = text.startsWith("**", index) ? "**" :
        (text[index] === "*" ? "*" : null);
      if (marker) {
        const end = closingMarker(text, marker, index + marker.length);
        const inner = end >= 0 ? text.slice(index + marker.length, end) : "";
        if (end >= 0 && inner.trim()) {
          flush();
          const element = document.createElement(marker === "**" ? "strong" : "em");
          appendFormattedText(element, inner);
          parent.appendChild(element);
          index = end + marker.length;
          continue;
        }
      }
      plain += text[index];
      index += 1;
    }
    flush();
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
    const nextContent = document.createElement("div");
    appendFormattedText(nextContent, text);
    try {
      renderMathInElement(nextContent, {
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
    // Prepare the complete answer offscreen; never empty the visible content.
    content.replaceChildren(...nextContent.childNodes);

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
    strongNodes: content.querySelectorAll("strong").length,
    emphasisNodes: content.querySelectorAll("em").length,
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

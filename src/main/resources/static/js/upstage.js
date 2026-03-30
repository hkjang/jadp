(function () {
    const parseEndpoint = "/v1/document-digitization";
    const oacDetectEndpoint = "/v1/pii-masker/oac/detect";
    const oacMaskEndpoint = "/v1/pii-masker/oac/mask";

    document.addEventListener("DOMContentLoaded", () => {
        bindDropzone("parse-dropzone", "parse-file", "parse-selected-file");
        bindDropzone("oac-dropzone", "oac-file", "oac-selected-file");
        bindTabs();
        bindFormResets();
        bindLivePreviews();

        document.getElementById("parse-submit")?.addEventListener("click", submitParse);
        document.getElementById("oac-detect-submit")?.addEventListener("click", () => submitOac("detect"));
        document.getElementById("oac-mask-submit")?.addEventListener("click", () => submitOac("mask"));
        refreshParseRequestPreview();
        refreshOacRequestPreview();
    });

    function bindDropzone(dropzoneId, inputId, labelId) {
        const dropzone = document.getElementById(dropzoneId);
        const input = document.getElementById(inputId);
        const label = document.getElementById(labelId);
        if (!dropzone || !input || !label) {
            return;
        }

        const refreshLabel = () => {
            label.textContent = input.files?.length ? input.files[0].name : "선택된 파일이 없습니다.";
        };

        input.addEventListener("change", refreshLabel);
        input.addEventListener("change", () => {
            if (inputId === "parse-file") {
                refreshParseRequestPreview();
            }
            if (inputId === "oac-file") {
                refreshOacRequestPreview();
            }
        });

        ["dragenter", "dragover"].forEach(eventName => {
            dropzone.addEventListener(eventName, event => {
                event.preventDefault();
                dropzone.classList.add("dragover");
            });
        });

        ["dragleave", "drop"].forEach(eventName => {
            dropzone.addEventListener(eventName, event => {
                event.preventDefault();
                dropzone.classList.remove("dragover");
            });
        });

        dropzone.addEventListener("drop", event => {
            if (!event.dataTransfer?.files?.length) {
                return;
            }
            input.files = event.dataTransfer.files;
            refreshLabel();
        });
    }

    function bindTabs() {
        document.querySelectorAll(".tabs").forEach(tabGroup => {
            const tabs = Array.from(tabGroup.querySelectorAll(".tab"));
            tabs.forEach(tab => {
                tab.addEventListener("click", () => {
                    tabs.forEach(other => other.classList.toggle("active", other === tab));
                    tabs.forEach(other => {
                        const pane = document.getElementById(other.dataset.target);
                        if (pane) {
                            pane.hidden = other !== tab;
                        }
                    });
                });
            });
        });
    }

    function bindFormResets() {
        document.getElementById("parse-reset")?.addEventListener("click", () => {
            window.setTimeout(() => {
                document.getElementById("parse-selected-file").textContent = "선택된 파일이 없습니다.";
                setText("parse-status", "대기 중");
                setParseSummary("-", "-", "-");
                setText("parse-markdown-output", "아직 결과가 없습니다.");
                setText("parse-text-output", "아직 결과가 없습니다.");
                setText("parse-json-output", "아직 결과가 없습니다.");
                renderParseElements([]);
                setIframeHtml("parse-html-output", "");
                refreshParseRequestPreview();
            }, 0);
        });

        document.getElementById("oac-reset")?.addEventListener("click", () => {
            window.setTimeout(() => {
                document.getElementById("oac-selected-file").textContent = "선택된 파일이 없습니다.";
                setText("oac-status", "대기 중");
                setOacSummary("-", "-", "-");
                setText("oac-json-output", "아직 결과가 없습니다.");
                setMaskedOutput(null);
                renderOacItems([]);
                refreshOacRequestPreview();
            }, 0);
        });
    }

    function bindLivePreviews() {
        bindPreviewFields([
            "parse-model",
            "parse-ocr",
            "parse-base64-encoding",
            "parse-password",
            "parse-pages",
            "parse-reading-order",
            "parse-table-method",
            "parse-image-output",
            "parse-image-format",
            "parse-hybrid",
            "parse-hybrid-mode",
            "parse-hybrid-url",
            "parse-hybrid-timeout",
            "parse-keep-line-breaks",
            "parse-use-struct-tree",
            "parse-include-header-footer",
            "parse-hybrid-fallback"
        ], refreshParseRequestPreview);

        bindPreviewFields([
            "oac-wrap-image"
        ], refreshOacRequestPreview);
    }

    function bindPreviewFields(ids, callback) {
        ids.forEach(id => {
            const node = document.getElementById(id);
            if (!node) {
                return;
            }
            const eventName = node.tagName === "SELECT" || node.type === "checkbox" || node.type === "file"
                ? "change"
                : "input";
            node.addEventListener(eventName, callback);
            if (eventName !== "change") {
                node.addEventListener("change", callback);
            }
        });
    }

    async function submitParse() {
        const file = getSelectedFile("parse-file");
        if (!file) {
            setStatus("parse-status", "파일을 먼저 선택해 주세요.", true);
            return;
        }

        const formData = new FormData();
        formData.append("document", file);
        appendValue(formData, "model", valueOf("parse-model"));
        appendValue(formData, "ocr", valueOf("parse-ocr"));
        appendValue(formData, "base64_encoding", valueOf("parse-base64-encoding"));
        appendValue(formData, "password", valueOf("parse-password"));
        appendValue(formData, "pages", valueOf("parse-pages"));
        appendValue(formData, "reading_order", valueOf("parse-reading-order"));
        appendValue(formData, "table_method", valueOf("parse-table-method"));
        appendValue(formData, "image_output", valueOf("parse-image-output"));
        appendValue(formData, "image_format", valueOf("parse-image-format"));
        appendValue(formData, "hybrid", valueOf("parse-hybrid"));
        appendValue(formData, "hybrid_mode", valueOf("parse-hybrid-mode"));
        appendValue(formData, "hybrid_url", valueOf("parse-hybrid-url"));
        appendValue(formData, "hybrid_timeout", valueOf("parse-hybrid-timeout"));
        appendChecked(formData, "keep_line_breaks", "parse-keep-line-breaks");
        appendChecked(formData, "use_struct_tree", "parse-use-struct-tree");
        appendChecked(formData, "include_header_footer", "parse-include-header-footer");
        appendChecked(formData, "hybrid_fallback", "parse-hybrid-fallback");

        setStatus("parse-status", "Parse 호출 중...", false);
        try {
            const response = await fetchJson(parseEndpoint, formData);
            renderParseResponse(response);
            setStatus("parse-status",
                "완료: model=" + fallback(response.model, "-")
                + ", pages=" + fallback(response.usage?.pages, 0)
                + ", elements=" + fallback(response.elements?.length, 0),
                false);
        } catch (error) {
            setParseSummary("-", "-", "-");
            renderParseElements([]);
            setText("parse-json-output", formatError(error));
            setText("parse-markdown-output", "오류가 발생했습니다.");
            setText("parse-text-output", formatError(error));
            setIframeHtml("parse-html-output", "");
            setStatus("parse-status", formatError(error), true);
        }
    }

    async function submitOac(mode) {
        const file = getSelectedFile("oac-file");
        if (!file) {
            setStatus("oac-status", "파일을 먼저 선택해 주세요.", true);
            return;
        }

        const endpoint = mode === "mask" ? oacMaskEndpoint : oacDetectEndpoint;
        const formData = new FormData();
        formData.append("document", file);
        formData.append("wrap_image_as_pdf", checked("oac-wrap-image") ? "true" : "false");

        setStatus("oac-status", "PII " + mode.toUpperCase() + " 호출 중...", false);
        try {
            const response = await fetchJson(endpoint, formData);
            renderOacResponse(response);
            setStatus("oac-status",
                "완료: schema=" + fallback(response.schema_version, "-")
                + ", pages=" + fallback(response.metadata?.page_count, 0)
                + ", items=" + fallback(response.items?.length, 0),
                false);
        } catch (error) {
            setOacSummary("-", "-", "-");
            renderOacItems([]);
            setMaskedOutput(null);
            setText("oac-json-output", formatError(error));
            setStatus("oac-status", formatError(error), true);
        }
    }

    async function fetchJson(url, formData) {
        const response = await fetch(url, {
            method: "POST",
            body: formData
        });
        const text = await response.text();
        let payload;
        try {
            payload = text ? JSON.parse(text) : {};
        } catch (error) {
            payload = { raw: text };
        }

        if (!response.ok) {
            const message = payload.message || payload.error || payload.raw || (response.status + " " + response.statusText);
            throw new Error(message);
        }
        return payload;
    }

    function renderParseResponse(response) {
        const content = response.content || {};
        const elements = Array.isArray(response.elements) ? response.elements : [];
        setParseSummary(
            fallback(response.model, "-"),
            fallback(response.usage?.pages, 0),
            elements.length
        );
        setText("parse-markdown-output", fallback(content.markdown, "Markdown 결과가 없습니다."));
        setText("parse-text-output", fallback(content.text, "Text 결과가 없습니다."));
        setText("parse-json-output", JSON.stringify(response, null, 2));
        setIframeHtml("parse-html-output", content.html || "");
        renderParseElements(elements);
    }

    function renderOacResponse(response) {
        const items = Array.isArray(response.items) ? response.items : [];
        setOacSummary(
            fallback(response.schema_version, "-"),
            fallback(response.metadata?.page_count, 0),
            items.length
        );
        setText("oac-json-output", JSON.stringify(response, null, 2));
        setMaskedOutput(response.masked_document || null);
        renderOacItems(items);
    }

    function refreshParseRequestPreview() {
        const file = getSelectedFile("parse-file");
        const lines = [
            "curl -X POST http://localhost:39080/v1/document-digitization \\",
            "  -F \"document=@" + (file ? file.name : "sample.pdf") + "\""
        ];

        const coreFields = [
            ["model", valueOf("parse-model")],
            ["ocr", valueOf("parse-ocr")],
            ["base64_encoding", valueOf("parse-base64-encoding")]
        ];
        coreFields.forEach(([key, value]) => {
            if (value) {
                lines.push("  -F \"" + key + "=" + escapePreview(value) + "\" \\");
            }
        });

        const extensionFields = [
            ["password", valueOf("parse-password")],
            ["pages", valueOf("parse-pages")],
            ["reading_order", valueOf("parse-reading-order")],
            ["table_method", valueOf("parse-table-method")],
            ["image_output", valueOf("parse-image-output")],
            ["image_format", valueOf("parse-image-format")],
            ["hybrid", valueOf("parse-hybrid")],
            ["hybrid_mode", valueOf("parse-hybrid-mode")],
            ["hybrid_url", valueOf("parse-hybrid-url")],
            ["hybrid_timeout", valueOf("parse-hybrid-timeout")]
        ];

        const toggles = [
            ["keep_line_breaks", checked("parse-keep-line-breaks")],
            ["use_struct_tree", checked("parse-use-struct-tree")],
            ["include_header_footer", checked("parse-include-header-footer")],
            ["hybrid_fallback", checked("parse-hybrid-fallback")]
        ];

        const activeExtensions = extensionFields.filter(([, value]) => value);
        const activeToggles = toggles.filter(([, enabled]) => enabled);
        if (activeExtensions.length || activeToggles.length) {
            lines.push("  # JADP extensions");
        }
        activeExtensions.forEach(([key, value]) => {
            lines.push("  -F \"" + key + "=" + escapePreview(value) + "\" \\");
        });
        activeToggles.forEach(([key]) => {
            lines.push("  -F \"" + key + "=true\" \\");
        });

        normalizePreview(lines);
        setText("parse-request-preview", lines.join("\n"));
    }

    function refreshOacRequestPreview() {
        const file = getSelectedFile("oac-file");
        const lines = [
            "curl -X POST http://localhost:39080/v1/pii-masker/oac/detect \\",
            "  -F \"document=@" + (file ? file.name : "sample.pdf") + "\""
        ];

        if (checked("oac-wrap-image")) {
            lines.push("  # JADP extension");
            lines.push("  -F \"wrap_image_as_pdf=true\" \\");
        }

        normalizePreview(lines);
        setText("oac-request-preview", lines.join("\n"));
    }

    function normalizePreview(lines) {
        for (let index = lines.length - 1; index >= 0; index--) {
            if (lines[index].trim().endsWith("\\")) {
                lines[index] = lines[index].replace(/\s*\\$/, "");
                break;
            }
        }
    }

    function renderParseElements(elements) {
        const container = document.getElementById("parse-elements");
        container.innerHTML = "";
        if (!elements.length) {
            container.appendChild(makeMutedCard("아직 element 결과가 없습니다."));
            return;
        }

        elements.slice(0, 60).forEach((element, index) => {
            const card = document.createElement("div");
            card.className = "result-item";

            const title = document.createElement("div");
            const category = document.createElement("span");
            category.className = "pill";
            category.textContent = fallback(element.category, "unknown");
            const page = document.createElement("span");
            page.className = "pill warn";
            page.textContent = "page " + fallback(element.page, "-");
            title.appendChild(category);
            title.appendChild(page);

            const text = document.createElement("div");
            text.style.marginTop = "10px";
            text.textContent = truncate(fallback(element.content?.text, "(text 없음)"), 320);

            const coords = document.createElement("div");
            coords.className = "vertices";
            coords.textContent = "coords: " + formatCoordinates(element.coordinates);

            card.appendChild(title);
            card.appendChild(text);
            card.appendChild(coords);

            if (index === 59 && elements.length > 60) {
                const more = document.createElement("div");
                more.className = "vertices";
                more.textContent = "나머지 " + (elements.length - 60) + "개 element는 Raw JSON에서 확인하세요.";
                card.appendChild(more);
            }

            container.appendChild(card);
        });
    }

    function renderOacItems(items) {
        const container = document.getElementById("oac-items");
        container.innerHTML = "";
        if (!items.length) {
            container.appendChild(makeMutedCard("아직 개인정보 탐지 결과가 없습니다."));
            return;
        }

        items.forEach(item => {
            const card = document.createElement("div");
            card.className = "result-item";

            const badges = document.createElement("div");
            const type = document.createElement("span");
            type.className = "pill";
            type.textContent = fallback(item.type, "UNKNOWN");
            const source = document.createElement("span");
            source.className = "pill warn";
            source.textContent = fallback(item.detection_source, "source 없음");
            badges.appendChild(type);
            badges.appendChild(source);

            const original = document.createElement("div");
            original.style.marginTop = "10px";
            original.innerHTML = "<strong>원문</strong><div>" + escapeHtml(fallback(item.value, "-")) + "</div>";

            const masked = document.createElement("div");
            masked.style.marginTop = "10px";
            masked.innerHTML = "<strong>마스킹</strong><div>" + escapeHtml(fallback(item.masked_value, "-")) + "</div>";

            const label = document.createElement("div");
            label.className = "vertices";
            label.textContent = "label: " + fallback(item.label, "-");

            const boxes = Array.isArray(item.bounding_boxes) ? item.bounding_boxes : [];
            const bbox = document.createElement("div");
            bbox.className = "vertices";
            bbox.textContent = "bbox: " + (boxes.length
                ? boxes.map(box => "page " + fallback(box.page, "-") + " " + formatVertices(box.vertices)).join(" | ")
                : "없음");

            card.appendChild(badges);
            card.appendChild(original);
            card.appendChild(masked);
            card.appendChild(label);
            card.appendChild(bbox);
            container.appendChild(card);
        });
    }

    function setParseSummary(model, pages, elements) {
        updateSummary("parse-summary", [
            [model, "model"],
            [String(pages), "pages"],
            [String(elements), "elements"]
        ]);
    }

    function setOacSummary(schema, pages, items) {
        updateSummary("oac-summary", [
            [schema, "schema"],
            [String(pages), "pages"],
            [String(items), "items"]
        ]);
    }

    function updateSummary(containerId, items) {
        const container = document.getElementById(containerId);
        if (!container) {
            return;
        }
        container.innerHTML = "";
        items.forEach(([value, label]) => {
            const card = document.createElement("div");
            card.className = "summary-item";
            const strong = document.createElement("strong");
            strong.textContent = value;
            const span = document.createElement("span");
            span.className = "muted";
            span.textContent = label;
            card.appendChild(strong);
            card.appendChild(span);
            container.appendChild(card);
        });
    }

    function setMaskedOutput(maskedDocument) {
        const container = document.getElementById("oac-masked-output");
        if (!container) {
            return;
        }
        container.innerHTML = "";
        if (!maskedDocument || !maskedDocument.url) {
            container.textContent = "아직 masked 파일이 없습니다.";
            return;
        }

        const meta = document.createElement("div");
        meta.innerHTML =
            "<strong>" + escapeHtml(fallback(maskedDocument.filename, maskedDocument.file_id || "masked-file")) + "</strong>"
            + "<div class='muted'>" + escapeHtml(fallback(maskedDocument.content_type, "-")) + "</div>";

        const link = document.createElement("a");
        link.className = "chip download-link";
        link.href = maskedDocument.url;
        link.target = "_blank";
        link.rel = "noopener";
        link.textContent = "Masked 파일 다운로드";

        container.appendChild(meta);
        container.appendChild(link);
    }

    function setStatus(id, message, isError) {
        const node = document.getElementById(id);
        if (!node) {
            return;
        }
        node.textContent = message;
        node.style.color = isError ? "#9f2f2f" : "";
    }

    function setText(id, value) {
        const node = document.getElementById(id);
        if (node) {
            node.textContent = value;
        }
    }

    function setIframeHtml(id, html) {
        const frame = document.getElementById(id);
        if (!frame) {
            return;
        }
        const body = html && html.trim()
            ? html
            : "<!doctype html><html><body style='font-family: sans-serif; color:#555;'>HTML 결과가 없습니다.</body></html>";
        frame.srcdoc = body;
    }

    function getSelectedFile(inputId) {
        const input = document.getElementById(inputId);
        return input?.files?.[0] || null;
    }

    function valueOf(id) {
        return document.getElementById(id)?.value?.trim() || "";
    }

    function checked(id) {
        return Boolean(document.getElementById(id)?.checked);
    }

    function appendValue(formData, name, value) {
        if (value) {
            formData.append(name, value);
        }
    }

    function appendChecked(formData, name, inputId) {
        if (checked(inputId)) {
            formData.append(name, "true");
        }
    }

    function fallback(value, replacement) {
        return value === undefined || value === null || value === "" ? replacement : value;
    }

    function truncate(text, max) {
        return text.length > max ? text.slice(0, max) + "..." : text;
    }

    function formatCoordinates(coordinates) {
        if (!Array.isArray(coordinates) || !coordinates.length) {
            return "없음";
        }
        return coordinates
            .map(coord => "(" + fallback(coord.x, "-") + ", " + fallback(coord.y, "-") + ")")
            .join(" ");
    }

    function formatVertices(vertices) {
        if (!Array.isArray(vertices) || !vertices.length) {
            return "없음";
        }
        return vertices
            .map(vertex => "(" + fallback(vertex.x, "-") + ", " + fallback(vertex.y, "-") + ")")
            .join(" ");
    }

    function makeMutedCard(text) {
        const card = document.createElement("div");
        card.className = "result-item muted";
        card.textContent = text;
        return card;
    }

    function formatError(error) {
        return error instanceof Error ? error.message : String(error);
    }

    function escapePreview(value) {
        return String(value).replace(/"/g, "\\\"");
    }

    function escapeHtml(value) {
        return String(value)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }
})();

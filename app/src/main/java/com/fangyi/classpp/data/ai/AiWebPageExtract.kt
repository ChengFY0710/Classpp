package com.fangyi.classpp.data.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 「AI 识别教务网页」的页面内容提取：注入静态脚本，把当前页面整理成 AI 可读的纯文本。
 *
 * 为什么不让 AI 直接访问 URL：教务课表页需要用户登录态，模型侧拿不到 WebView 的 cookie，
 * 因此必须由 App 在页面上下文里取内容，再把文本交给模型解析。
 *
 * 提取口径（与 [AiScheduleWebImportPrompt] 的输入说明一一对应）：
 * - 表格：所有 `<table>` 按 rowspan/colspan 展开成 TSV 网格（单元格 `\t` 分隔、行 `\n`
 *   分隔，被合并覆盖的位置留空），先收叶子表再收布局表，最多 20 张、约 16000 字符；
 * - 可见文本：`body.innerText`，最多约 6000 字符；
 * - 下拉框当前选中值：辅助模型判断当前学期；
 * - 同源 iframe 递归读取；跨域 iframe 因同源策略读不到（表现为页面内容缺失，由 AI/UI
 *   的「没有识别到课表」提示用户换页重试）。
 *
 * 脚本是常量、不拼接任何用户输入；evaluateJavascript 不等待 Promise，脚本同步写结果变量，
 * 原生侧轮询读取（同 `WebViewTimetableJs` 的约定）。
 */
internal object AiWebPageExtract {

    const val RESULT_VAR: String = "window.__classppAiWebPage"

    /** 每次抓取前清空结果，避免读到上一次的旧内容 */
    fun resetResult(): String = "$RESULT_VAR = ''"

    /** 轮询读取表达式：未写回时返回空串 */
    fun readResult(): String = "($RESULT_VAR || '')"

    /** 解析脚本写回的 JSON 信封；null = 不是合法信封 */
    fun parse(raw: String?): AiWebPageCapture? {
        if (raw.isNullOrBlank()) return null
        return try {
            Json.decodeFromString<AiWebPageCapture>(raw)
        } catch (_: Exception) {
            null
        }
    }

    /** 注入脚本：同步执行，结果写入 [RESULT_VAR] */
    val script: String = """
        (function () {
          var MAX_TABLES = 20;
          var MAX_TABLE_CHARS = 16000;
          var MAX_TEXT_CHARS = 6000;
          var MAX_CELL_CHARS = 300;
          var MAX_ROWS = 200;
          var MAX_COLS = 40;

          function norm(s) {
            if (!s) return '';
            return String(s)
              .replace(/\u00a0/g, ' ')
              .replace(/\r\n?/g, '\n')
              .replace(/[ \t]+/g, ' ')
              .replace(/ *\n */g, '\n')
              .replace(/\n{3,}/g, '\n\n')
              .trim();
          }

          function cellText(cell) {
            var t = norm(cell.innerText || cell.textContent || '');
            if (t.length > MAX_CELL_CHARS) t = t.slice(0, MAX_CELL_CHARS) + '…';
            return t;
          }

          function tableGrid(table) {
            var rows = table.rows;
            if (!rows || !rows.length) return '';
            var occupied = [];
            var lines = [];
            var rowLimit = Math.min(rows.length, MAX_ROWS);
            for (var r = 0; r < rowLimit; r++) {
              var row = rows[r];
              var cells = row.cells || [];
              var cols = [];
              var c = 0;
              for (var i = 0; i < cells.length; i++) {
                while (occupied[r] && occupied[r][c]) c++;
                if (c >= MAX_COLS) break;
                var cell = cells[i];
                var text = cellText(cell);
                var rs = cell.rowSpan || 1;
                var cs = cell.colSpan || 1;
                if (rs < 1) rs = 1;
                if (cs < 1) cs = 1;
                if (rs > MAX_ROWS) rs = MAX_ROWS;
                if (cs > MAX_COLS) cs = MAX_COLS;
                cols[c] = text;
                for (var dr = 0; dr < rs; dr++) {
                  var rr = r + dr;
                  if (!occupied[rr]) occupied[rr] = [];
                  for (var dc = 0; dc < cs; dc++) {
                    if (c + dc < MAX_COLS) occupied[rr][c + dc] = true;
                  }
                }
                c += cs;
              }
              var lineParts = [];
              for (var k = 0; k < MAX_COLS; k++) {
                if (cols[k] !== undefined) lineParts.push(cols[k]);
                else if (k < c) lineParts.push('');
                else break;
              }
              while (lineParts.length && lineParts[lineParts.length - 1] === '') lineParts.pop();
              lines.push(lineParts.join('\t'));
            }
            return lines.join('\n');
          }

          function collect(doc, out) {
            var tables = doc.querySelectorAll ? doc.querySelectorAll('table') : [];
            // 先收「叶子表」再收包含子表的布局表：老教务常用外层布局表包住课表，
            // 若按 DOM 顺序先收外层，网格会重复整页内容并把课表的预算挤掉。
            // 上限 200 张：防止病态页面让逐表 querySelector 退化
            var leaves = [];
            var parents = [];
            var tableCount = Math.min(tables.length, 200);
            for (var i = 0; i < tableCount; i++) {
              var nested = tables[i].querySelector ? tables[i].querySelector('table') : null;
              if (nested) parents.push(tables[i]);
              else leaves.push(tables[i]);
            }
            var ordered = leaves.concat(parents);
            for (var t = 0; t < ordered.length; t++) {
              if (out.tables >= MAX_TABLES) break;
              var grid = tableGrid(ordered[t]);
              // 单行表几乎没有信息量，跳过以给课表留预算
              if (!grid || grid.indexOf('\n') < 0) continue;
              out.tables++;
              out.parts.push('[TABLE ' + out.tables + ']\n' + grid);
            }

            var selects = doc.querySelectorAll ? doc.querySelectorAll('select') : [];
            for (var s = 0; s < selects.length && out.selects.length < 10; s++) {
              var sel = selects[s];
              var label = sel.getAttribute('name') || sel.getAttribute('id') || ('select' + (s + 1));
              var opt = sel.options && sel.selectedIndex >= 0 ? sel.options[sel.selectedIndex] : null;
              var val = opt ? norm(opt.text || opt.value || '') : '';
              if (val) out.selects.push(label + ': ' + val);
            }

            var text = '';
            if (doc.body) text = norm(doc.body.innerText || doc.body.textContent || '');
            if (text) out.texts.push(text);

            var frames = doc.querySelectorAll ? doc.querySelectorAll('iframe,frame') : [];
            for (var f = 0; f < frames.length; f++) {
              try {
                var fd = frames[f].contentDocument;
                if (fd) collect(fd, out);
              } catch (e) {}
            }
          }

          try {
            var out = { tables: 0, parts: [], selects: [], texts: [] };
            collect(document, out);

            var tableText = out.parts.join('\n\n');
            if (tableText.length > MAX_TABLE_CHARS) {
              tableText = tableText.slice(0, MAX_TABLE_CHARS) + '\n[表格内容过长，已截断]';
            }
            var textText = out.texts.join('\n\n');
            if (textText.length > MAX_TEXT_CHARS) {
              textText = textText.slice(0, MAX_TEXT_CHARS) + '\n[可见文本过长，已截断]';
            }

            var content = '';
            content += 'URL: ' + (location.href || '') + '\n';
            content += 'TITLE: ' + (document.title || '') + '\n';
            if (out.selects.length) {
              content += '\n[已选下拉项]\n' + out.selects.join('\n') + '\n';
            }
            if (tableText) {
              content += '\n' + tableText + '\n';
            }
            if (textText) {
              content += '\n[可见文本]\n' + textText + '\n';
            }

            if (!tableText && !textText) {
              window.__classppAiWebPage = JSON.stringify({ ok: false, reason: 'empty', content: '' });
            } else {
              window.__classppAiWebPage = JSON.stringify({ ok: true, reason: '', content: content });
            }
          } catch (e) {
            window.__classppAiWebPage = JSON.stringify({ ok: false, reason: 'error', content: '' });
          }
        })();
    """.trimIndent()
}

/** 页面抓取结果信封：ok = 成功提取到内容，content 为整理后的纯文本 */
@Serializable
internal data class AiWebPageCapture(
    val ok: Boolean = false,
    val content: String = "",
    val reason: String = "",
)

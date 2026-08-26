package com.mhp.booksystem.controller.internal;

import com.mhp.booksystem.mapper.IkCustomWordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * IK 远程词库接口 — 供 Elasticsearch 内的 IK 分词器插件轮询拉取。
 *
 * ── IK 热更新原理 ────────────────────────────────────────────────────────────
 * IK 插件通过 HTTP 轮询以下两个端点（轮询间隔由 ES 配置控制，默认 60 秒）：
 *   GET /internal/ik/ext-words   ← 扩展词库（让 IK 认识新词）
 *   GET /internal/ik/stop-words  ← 停止词库（让 IK 过滤无意义词）
 *
 * 每次轮询流程：
 *   1. IK 发出 GET 请求。
 *   2. 本接口返回响应头 Last-Modified = MAX(update_time) of active words。
 *   3. IK 对比 Last-Modified 与上次拉取时记录的值：
 *        ┌─ 相同 → 词库未变化，跳过重载（节省资源）
 *        └─ 不同 → 读取响应体（纯文本，每行一词），热重载词库
 *   4. 热重载不需要重启 ES，词库变更在下个轮询周期内自动生效。
 *
 * ── 响应格式要求 ─────────────────────────────────────────────────────────────
 * - Content-Type: text/plain; charset=UTF-8（IK 按行读取，不能是 JSON）
 * - Last-Modified: HTTP RFC 1123 日期（如 "Mon, 25 Aug 2026 02:30:00 GMT"）
 * - 响应体：每行一个词条，不含空行，不含注释
 *
 * ── 接口安全 ─────────────────────────────────────────────────────────────────
 * 端点映射在 /internal/ 路径下，Gateway 不转发此前缀，仅 Docker 内网可访问。
 * IKAnalyzer.cfg.xml 中填写的 URL 为 http://host.docker.internal:8081/internal/ik/...，
 * host.docker.internal 在 Docker Desktop（Windows/macOS）中指向宿主机，
 * 即 mhp-account 服务所在的主机，容器外部无法通过此域名访问。
 */
@RestController
@RequestMapping("/internal/ik")
@RequiredArgsConstructor
public class IkDictController {

    // type 常量：与 ik_custom_word.type 字段值对应
    private static final int TYPE_EXT  = 1;  // 扩展词
    private static final int TYPE_STOP = 2;  // 停止词

    // RFC 1123 日期格式，HTTP Last-Modified 标准格式
    // 示例："Mon, 25 Aug 2026 02:30:00 GMT"
    private static final DateTimeFormatter HTTP_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

    private final IkCustomWordMapper ikCustomWordMapper;

    /**
     * 扩展词库接口 — IK 识别新词用。
     *
     * 词条来源：ik_custom_word 表中 type=1（扩展词）且 status=1（生效）的所有记录。
     * 触发热更新：任意扩展词被新增/修改/禁用，其 update_time 变化，
     *            下个轮询周期 IK 检测到 Last-Modified 不同，自动重载词库。
     */
    @GetMapping(value = "/ext-words", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> extWords() {
        return buildDictResponse(TYPE_EXT);
    }

    /**
     * 停止词库接口 — IK 过滤无意义词用。
     *
     * 停止词被 IK 在分词时直接丢弃，不进入倒排索引。
     * 适合添加：圈内口头语（"求求"、"大佬"）、礼貌用语（"谢谢"）等搜索无意义的词。
     */
    @GetMapping(value = "/stop-words", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> stopWords() {
        return buildDictResponse(TYPE_STOP);
    }

    /**
     * 构建词库 HTTP 响应。
     *
     * @param type 词条类型（1=扩展词，2=停止词）
     * @return 纯文本响应体（每行一词）+ Last-Modified 头
     */
    private ResponseEntity<String> buildDictResponse(int type) {
        List<String> words = ikCustomWordMapper.selectActiveWords(type);
        LocalDateTime maxUpdateTime = ikCustomWordMapper.selectMaxUpdateTime(type);

        // 响应体：每行一个词，用 \n 分隔（IK 按行读取，不要 \r\n 避免 Windows 换行问题）
        String body = String.join("\n", words);

        HttpHeaders headers = new HttpHeaders();
        if (maxUpdateTime != null) {
            // 将 LocalDateTime（本地时区）转换为 UTC 后格式化为 RFC 1123
            // IK 客户端只关心这个值有没有变化，不关心时区是否精确，
            // 但统一用 UTC 是标准做法
            ZonedDateTime utc = maxUpdateTime.atZone(ZoneId.systemDefault())
                                             .withZoneSameInstant(ZoneId.of("UTC"));
            headers.set(HttpHeaders.LAST_MODIFIED, HTTP_DATE_FORMATTER.format(utc));
        }

        return ResponseEntity.ok()
                .headers(headers)
                .body(body);
    }
}

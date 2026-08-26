FROM elasticsearch:8.13.0

# ── 插件安装 ─────────────────────────────────────────────────────────────────
# 版本必须与 ES 镜像版本完全一致，否则启动时报 PluginException

# 1. IK 中文分词器
#    ik_max_word：最细粒度，用于建索引（召回率高）
#    ik_smart：   最粗粒度，用于搜索（精准率高）
RUN ./bin/elasticsearch-plugin install --batch \
    https://get.infini.cloud/elasticsearch/analysis-ik/8.13.0

# 2. 拼音分词器（analysis-pinyin）
#    将中文词转换为拼音，支持"zhuangniang"→"妆娘"的拼音搜索
#    本项目中与 IK 组合使用（先 IK 切词，再 pinyin 转拼音），
#    定义为自定义 analyzer "ik_pinyin"，配置见 merchant-settings.json
RUN ./bin/elasticsearch-plugin install --batch \
    https://get.infini.cloud/elasticsearch/analysis-pinyin/8.13.0

# ── IK 远程词库配置 ──────────────────────────────────────────────────────────
# IK 每 60 秒轮询此配置中的 URL，Last-Modified 变化时自动热重载词库
# URL 指向 mhp-account 服务的 /internal/ik/ext-words 和 /internal/ik/stop-words
COPY ik-config/IKAnalyzer.cfg.xml \
     /usr/share/elasticsearch/plugins/analysis-ik/config/IKAnalyzer.cfg.xml

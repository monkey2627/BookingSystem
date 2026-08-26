FROM elasticsearch:8.13.0

# 预装 IK 中文分词器（版本必须与 ES 完全一致）
# ik_max_word：最细粒度切词，用于建索引（召回更多）
# ik_smart：最粗粒度切词，用于搜索（精准度更高）
RUN ./bin/elasticsearch-plugin install --batch https://get.infini.cloud/elasticsearch/analysis-ik/8.13.0

# 覆盖 IK 默认配置，启用远程词库热更新
# IK 插件安装后配置目录位于：/usr/share/elasticsearch/plugins/analysis-ik/config/
# 本地 ik-config/IKAnalyzer.cfg.xml 中填写了 mhp-account 提供的词库 HTTP 接口，
# IK 每 60 秒轮询一次，Last-Modified 有变化时自动热加载词库，无需重启 ES
COPY ik-config/IKAnalyzer.cfg.xml /usr/share/elasticsearch/plugins/analysis-ik/config/IKAnalyzer.cfg.xml

package com.mhp.booksystem.repository;

import com.mhp.booksystem.document.MerchantDoc;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * 商家 ES 数据访问接口。
 *
 * ElasticsearchRepository<MerchantDoc, Long> 已内置：
 *   save(doc)         → index（新增或全量覆盖）
 *   saveAll(docs)     → bulk index（批量，用于初始化全量导入）
 *   deleteById(id)    → delete by id
 *   findById(id)      → get by id
 *
 * 复杂搜索（bool query + 分页 + 排序）通过注入 ElasticsearchOperations 实现，
 * 见 MerchantServiceImpl.search()。
 */
@Repository
public interface MerchantEsRepository extends ElasticsearchRepository<MerchantDoc, Long> {
}

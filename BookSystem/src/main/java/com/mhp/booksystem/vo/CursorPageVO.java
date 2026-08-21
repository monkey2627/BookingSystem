package com.mhp.booksystem.vo;

import lombok.Data;

import java.util.List;

/**
 * 游标分页响应体
 *
 * 前端拿到 nextCursor 后，下次请求带上 lastId=nextCursor 即可获取下一页。
 * hasMore=false 时说明已经到最后一页，不需要再请求。
 */
@Data
public class CursorPageVO<T> {

    private List<T> list;

    /** 是否还有下一页 */
    private Boolean hasMore;

    /** 下一页的游标（当前页最后一条记录的 id），hasMore=false 时为 null */
    private Long nextCursor;

    public static <T> CursorPageVO<T> of(List<T> list, boolean hasMore, Long nextCursor) {
        CursorPageVO<T> vo = new CursorPageVO<>();
        vo.setList(list);
        vo.setHasMore(hasMore);
        vo.setNextCursor(hasMore ? nextCursor : null);
        return vo;
    }
}

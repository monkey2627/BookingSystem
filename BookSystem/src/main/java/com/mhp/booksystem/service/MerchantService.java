package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.MerchantUpdateDTO;
import com.mhp.booksystem.entity.Merchant;
import com.mhp.booksystem.vo.MerchantStatsVO;
import com.mhp.booksystem.vo.MerchantVO;

/**
 * 为什么继承 IService<Merchant>？
 *
 * IService 是 MyBatis-Plus 提供的通用 Service 接口，内置了几十个常用方法：
 * getById / save / saveOrUpdate / updateById / removeById / list / page / lambdaQuery ...
 * 继承后不需要在接口里逐一声明这些方法，实现类再继承 ServiceImpl 就自动拥有了全部实现。
 * 这里只需声明业务专属方法（updateInfo / getDetail / search）。
 *
 * 为什么接口不加 @Service？
 *
 * 接口无法被实例化，Spring 无法对接口创建 Bean，加了也没效果。
 * @Service 要加在实现类 MerchantServiceImpl 上，Spring 扫描到它后创建 Bean，
 * 并发现它实现了 MerchantService 接口，注入时自动匹配。
 * Controller 依赖接口而非实现类，是标准的面向接口编程——换实现类时 Controller 不用改。
 */
public interface MerchantService extends IService<Merchant> {

    /** 商家完善/更新自己的资料（当前登录用户） */
    void updateInfo(MerchantUpdateDTO dto);

    /** 查看商家主页 */
    MerchantVO getDetail(Long merchantId);

    /** 搜索商家，分页返回 */
    Page<MerchantVO> search(String city, Integer serviceType, String keyword, int page, int size);

    /** 获取当前登录商家自己的信息 */
    MerchantVO getMyInfo();

    /** 获取当前登录商家的数据统计（本月订单 + 评分） */
    MerchantStatsVO getStats();
}

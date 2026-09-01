package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.ComplaintCreateDTO;
import com.mhp.booksystem.entity.Complaint;
import com.mhp.booksystem.vo.ComplaintVO;

import java.util.List;

public interface ComplaintService extends IService<Complaint> {

    void create(ComplaintCreateDTO dto);

    List<ComplaintVO> listReceived();
}

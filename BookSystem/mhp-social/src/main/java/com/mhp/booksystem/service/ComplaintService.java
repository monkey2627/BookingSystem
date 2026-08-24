package com.mhp.booksystem.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.mhp.booksystem.dto.ComplaintCreateDTO;
import com.mhp.booksystem.entity.Complaint;

public interface ComplaintService extends IService<Complaint> {

    void create(ComplaintCreateDTO dto);
}

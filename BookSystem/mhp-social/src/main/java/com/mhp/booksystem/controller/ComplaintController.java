package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.ComplaintCreateDTO;
import com.mhp.booksystem.service.ComplaintService;
import com.mhp.booksystem.vo.ComplaintVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/complaint")
@RequiredArgsConstructor
public class ComplaintController {

    private final ComplaintService complaintService;

    @PostMapping
    public Result<?> create(@Valid @RequestBody ComplaintCreateDTO dto) {
        complaintService.create(dto);
        return Result.ok();
    }

    /** 当前登录用户收到的投诉列表（商家用） */
    @GetMapping("/received")
    public Result<List<ComplaintVO>> listReceived() {
        return Result.ok(complaintService.listReceived());
    }
}

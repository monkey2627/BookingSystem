package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.dto.MessageSendDTO;
import com.mhp.booksystem.service.MessageService;
import com.mhp.booksystem.vo.ConversationVO;
import com.mhp.booksystem.vo.CursorPageVO;
import com.mhp.booksystem.vo.MessageVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/message")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/conversations")
    public Result<List<ConversationVO>> getConversations() {
        return Result.ok(messageService.getConversations());
    }

    @PostMapping("/send")
    public Result<?> send(@Valid @RequestBody MessageSendDTO dto) {
        messageService.send(dto);
        return Result.ok();
    }

    @GetMapping("/history")
    public Result<CursorPageVO<MessageVO>> history(
            @RequestParam Long targetUserId,
            @RequestParam(required = false) Long lastId,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(messageService.history(targetUserId, lastId, size));
    }
}

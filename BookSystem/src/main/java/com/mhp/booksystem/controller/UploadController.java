package com.mhp.booksystem.controller;

import com.mhp.booksystem.common.Result;
import com.mhp.booksystem.common.ResultCode;
import com.mhp.booksystem.common.exception.BusinessException;
import com.mhp.booksystem.config.QiniuConfig;
import com.qiniu.http.Response;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.util.Auth;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController {

    private static final long MAX_SIZE = 5 * 1024 * 1024L; // 5MB
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    private final QiniuConfig qiniuConfig;

    @PostMapping
    public Result<String> upload(@RequestParam("file") MultipartFile file) {
        // 校验文件类型
        if (!ALLOWED_TYPES.contains(file.getContentType())) {
            throw new BusinessException(ResultCode.UPLOAD_FILE_TYPE_ERROR);
        }
        // 校验文件大小
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException(ResultCode.UPLOAD_FILE_TOO_LARGE);
        }

        try {
            String originalFilename = file.getOriginalFilename();
            String ext = originalFilename != null && originalFilename.contains(".")
                    ? originalFilename.substring(originalFilename.lastIndexOf("."))
                    : ".jpg";
            // 用 UUID 作为文件名，防止重名覆盖
            String fileName = UUID.randomUUID().toString().replace("-", "") + ext;

            Auth auth = Auth.create(qiniuConfig.getAccessKey(), qiniuConfig.getSecretKey());
            String upToken = auth.uploadToken(qiniuConfig.getBucket());

            UploadManager uploadManager = new UploadManager(new Configuration(Region.autoRegion()));
            Response response = uploadManager.put(file.getBytes(), fileName, upToken);

            if (!response.isOK()) {
                throw new BusinessException("文件上传失败，请重试");
            }

            String cdnUrl = qiniuConfig.getDomain() + "/" + fileName;
            return Result.ok(cdnUrl);

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("文件上传失败：" + e.getMessage());
        }
    }
}

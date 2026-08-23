package org.example.codesandbox.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;
import org.example.codesandbox.execute.JavaCodeSandBox;
import org.example.codesandbox.model.judge.enums.JudgeInfoMessageEnum;
import org.example.codesandbox.model.judge.execute.ExecuteMessage;
import org.example.codesandbox.model.judge.execute.ExecuteResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/docker")
@Slf4j
public class DockerController {
    private final static String JAVA = "java";

    private final static String AUTH = "auth";

    /**
     * 鉴权密钥，从 application.yml 的 sandbox.auth-secret 读取。
     * 客户端请求需带 auth 头 = md5Hex(该密钥)。
     */
    @Value("${sandbox.auth-secret:online_judge_project_secret_key}")
    private String authSecret;

    private String testSecret = "test";
    @Resource
    private JavaCodeSandBox javaCodeSandBox;

    @PostMapping
    public ExecuteResponse doExecuteDockerBox(@RequestBody ExecuteMessage executeMessage,
                                              HttpServletRequest request, HttpServletResponse response) {
        String user_secretKey = request.getHeader(AUTH);
        String secretKey = DigestUtil.md5Hex(authSecret);
        if (user_secretKey == null || !secretKey.equals(user_secretKey)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return null;
        }
        // 接口层校验：没有实际内容（code 空 / judgeCases 空）直接返回，不执行沙箱
        if (executeMessage == null
                || StrUtil.isBlank(executeMessage.getCode())
                || executeMessage.getJudgeCases() == null
                || executeMessage.getJudgeCases().isEmpty()
                || StrUtil.isBlank(executeMessage.getLanguage())) {
            log.warn("请求参数不完整：code 或 judgeCases 为空，跳过执行");
            return ExecuteResponse.builder()
                    .status(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue())
                    .errorMessage("code 和 judgeCases 不能为空")
                    .build();
        }
        try {
            if (executeMessage.getLanguage().equals(JAVA)) {
                return javaCodeSandBox.doExecuteCodeSandBoxDocker(executeMessage);
            }
            return ExecuteResponse.builder()
                    .status(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue())
                    .errorMessage("不支持的编程语言")
                    .build();
        } catch (Exception e) {
            log.info("error: ", e);
            return ExecuteResponse.builder()
                    .status(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue())
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}

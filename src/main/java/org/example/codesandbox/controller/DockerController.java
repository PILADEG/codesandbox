package org.example.codesandbox.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import lombok.extern.slf4j.Slf4j;
import org.example.codesandbox.model.judge.JudgeCase;
import org.example.codesandbox.model.judge.JudgeConfig;
import org.example.codesandbox.model.judge.enums.JudgeInfoMessageEnum;
import org.example.codesandbox.model.judge.execute.ExecuteMessage;
import org.example.codesandbox.model.judge.execute.ExecuteResponse;
import org.example.codesandbox.utils.ProcessUtils;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/docker")
@Slf4j
public class DockerController {
    private static final String GLOBAL_CODE_DIR_NAME ="SampleCodes";
    private static final String SIMPLE_CODE = "SimpleCode";
    private static final String MAIN = "Main";
    @PostMapping
    public ExecuteResponse doExecuteDockerBox(@RequestBody ExecuteMessage executeMessage) {
        // 接口层校验：没有实际内容（code 空 / judgeCases 空）直接返回，不执行沙箱
        if (executeMessage == null
                || StrUtil.isBlank(executeMessage.getCode())
                || executeMessage.getJudgeCases() == null
                || executeMessage.getJudgeCases().isEmpty()) {
            log.warn("请求参数不完整：code 或 judgeCases 为空，跳过执行");
            return ExecuteResponse.builder()
                    .status(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue())
                    .errorMessage("code 和 judgeCases 不能为空")
                    .build();
        }
        String userDir = System.getProperty("user.dir");
        String globalCodeDir = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        String code = executeMessage.getCode();
        log.info("code: {}", code);
        String userCodeParentPath = globalCodeDir + File.separator + UUID.randomUUID();
        File writeFile = FileUtil.writeString(code, userCodeParentPath + File.separator + MAIN+".java", "UTF-8");
        String compileCmd = String.format("javac -encoding utf-8 %s", writeFile.getAbsolutePath());
        try {
            Process process = Runtime.getRuntime().exec(compileCmd);
            ExecuteResponse executeResponse = ProcessUtils.buildExecuteResponse(process);
            log.info("executeResponse: {}", executeResponse);
            if (executeResponse.getStatus() !=null){
                return executeResponse;
            }
            // 挂载编译产物所在目录（含 SimpleCode.class），而不是类文件路径
            ExecuteResponse dockerResponse = ProcessUtils.doExecute(userCodeParentPath, executeMessage);
            log.info("dockerResponse: {}", dockerResponse);
            return  dockerResponse;
        } catch (Exception e) {
            log.error("System error", e);
            return ExecuteResponse.builder()
                    .status(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue())
                    .errorMessage(e.toString()).build();
        }finally {
            if (FileUtil.isDirectory(userCodeParentPath)){
                FileUtil.del(userCodeParentPath);
            }
        }
    }
    @GetMapping("/test")
    public String doExecuteDockerBoxTest() {
        String userDir = System.getProperty("user.dir");
        String globalCodeDir = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        String code = FileUtil.readString(globalCodeDir+File.separator+SIMPLE_CODE, "UTF-8");
        log.info("code: {}", code);
        log.info("globalCodeDir: {}", globalCodeDir);
        List<JudgeCase> judgeCases = Arrays.asList(new JudgeCase("1 2","3"),
                new JudgeCase("3 4","7"));
        // memoryLimit 单位为 MB（demo 用 128MB / 3s）
        JudgeConfig judgeConfig = new JudgeConfig(3000L, 128L);
        ExecuteMessage executeMessage =ExecuteMessage.builder()
                .judgeCases(judgeCases)
                .judgeConfig(judgeConfig)
                .build();
        String userCodeParentPath = globalCodeDir + File.separator + UUID.randomUUID();
        File writeFile = FileUtil.writeString(code, userCodeParentPath + File.separator + MAIN+".java", "UTF-8");
        String compileCmd = String.format("javac -encoding utf-8 %s", writeFile.getAbsolutePath());
        try {
            Process process = Runtime.getRuntime().exec(compileCmd);
            ExecuteResponse executeResponse = ProcessUtils.buildExecuteResponse(process);
            log.info("executeResponse: {}", executeResponse);
            // 挂载编译产物所在目录（含 SimpleCode.class），而不是类文件路径
            ExecuteResponse dockerResponse = ProcessUtils.doExecute(userCodeParentPath, executeMessage);
            log.info("dockerResponse: {}", dockerResponse);
        } catch (Exception e) {
            log.error("compileCmd error", e);
        }
        return "docker";
    }
}

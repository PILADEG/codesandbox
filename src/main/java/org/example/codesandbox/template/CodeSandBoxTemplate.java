package org.example.codesandbox.template;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.UUID;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.command.ExecStartResultCallback;
import lombok.extern.slf4j.Slf4j;
import org.example.codesandbox.model.judge.JudgeCase;
import org.example.codesandbox.model.judge.JudgeConfig;
import org.example.codesandbox.model.judge.enums.JudgeInfoMessageEnum;
import org.example.codesandbox.model.judge.execute.ExecuteMessage;
import org.example.codesandbox.model.judge.execute.ExecuteResponse;
import org.example.codesandbox.utils.ProcessUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public abstract class CodeSandBoxTemplate implements CodeSandBox {
    private static final String GLOBAL_CODE_DIR_NAME ="SampleCodes";

    private static final String SIMPLE_CODE = "SimpleCode";

    private static final String MAIN = "Main";

    private static final String RUN_IMAGE = "eclipse-temurin:8-jdk";

    /**
     * 容器内代码挂载目录
     */
    private static final String WORK_DIR = "/app";

    /**
     * 用户代码的类名（public class 必须与文件名一致）
     */
    private static final String CLASS_NAME = "SimpleCode";

    /**
     * 默认时间限制（ms）
     */
    private static final long DEFAULT_TIME_LIMIT = 3000L;

    /**
     * 默认内存限制（MB）
     */
    private static final long DEFAULT_MEMORY_LIMIT = 128L;


    @Resource
    private  DockerClient dockerClient;

    protected File saveCodeToFile(ExecuteMessage executeMessage) {
        String userDir = System.getProperty("user.dir");
        String globalCodeDir = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        String code = executeMessage.getCode();
        log.info("code: {}", code);
        String userCodeParentPath = globalCodeDir + File.separator + UUID.randomUUID();
        File writeFile = FileUtil.writeString(code, userCodeParentPath + File.separator + MAIN+".java", "UTF-8");
        return writeFile;
    }
    protected ExecuteResponse buildExecuteResponse(ExecuteMessage executeMessage,String path) {
        String compileCmd = String.format("javac -encoding utf-8 %s", path);
        try{
            Process process = Runtime.getRuntime().exec(compileCmd);
            ExecuteResponse executeResponse = ProcessUtils.buildExecuteResponse(process);
            log.info("executeResponse: {}", executeResponse);
            return executeResponse;
        } catch (Exception e) {
            log.error("buildExecuteResponse error", e);
            return ExecuteResponse.builder()
                    .status(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue())
                    .errorMessage(e.toString()).build();
        }
    }
    protected ExecuteResponse executeCode(ExecuteMessage executeMessage,String path) {
        String containerId = null;
        ExecuteResponse executeResponse = new ExecuteResponse();
        try {
            ProcessUtils.ensureImage(RUN_IMAGE);
            JudgeConfig judgeConfig = executeMessage.getJudgeConfig();
            // 单位约定：memoryLimit 以 MB 为单位（如 Postman 传 128 即 128MB）
            long memoryLimitMb = judgeConfig != null && judgeConfig.getMemoryLimit() != null
                    ? judgeConfig.getMemoryLimit() : DEFAULT_MEMORY_LIMIT;
            // Docker 要求容器内存下限 6MB，低于会被拒绝（400），这里兜底
            long memoryBytes = Math.max(memoryLimitMb, 6L) * 1024L * 1024L;
            long timeLimitMs = judgeConfig != null && judgeConfig.getTimeLimit() != null
                    ? judgeConfig.getTimeLimit() : DEFAULT_TIME_LIMIT;
            log.info("limit_memory: {} MB", memoryLimitMb);
            // 1. 创建常驻容器，挂载编译产物目录
            CreateContainerResponse container = dockerClient.createContainerCmd(RUN_IMAGE)
                    .withName("sandbox-" + java.util.UUID.randomUUID())
                    .withCmd("sleep", "infinity")          // 常驻，供后续多次 exec
                    .withNetworkDisabled(true)              // 断网
                    .withHostConfig(HostConfig.newHostConfig()
                            .withMemory(memoryBytes)         // 内存限制（bytes）
                            .withMemorySwap(0L)              // 禁止 swap
                            .withCpuShares(512)              // 约 0.5 核
                            .withPidsLimit(128L)             // 防 fork bomb
                            .withBinds(new Bind(path, new Volume(WORK_DIR))))
                    .exec();
            containerId = container.getId();
            dockerClient.startContainerCmd(containerId).exec();

            // 2. 每个用例 exec 一次 java，输入走 stdin（经挂载文件 + shell 重定向）
            List<String> outputList = new ArrayList<>();
            StringBuilder errorBuilder = new StringBuilder();
            int caseIndex = 0;
            Long run_time = 0L;
            long peakMemoryBytes = 0L;
            for (JudgeCase judgeCase : executeMessage.getJudgeCases()) {
                StopWatch stopWatch = new StopWatch();
                stopWatch.start();
                caseIndex++;
                String input = judgeCase.getInputCase() == null ? "" : judgeCase.getInputCase();
                File inputFile = new File(path, "input_" + caseIndex + ".txt");
                FileUtil.writeString(input, inputFile, "UTF-8");

                ExecCreateCmdResponse runCmd = dockerClient.execCreateCmd(containerId)
                        .withCmd("sh", "-c", String.format("java -cp %s %s < /app/input_%d.txt",
                                WORK_DIR, MAIN, caseIndex))
                        .withAttachStdout(true)
                        .withAttachStderr(true)
                        .exec();
                ByteArrayOutputStream stdout = new ByteArrayOutputStream();
                ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                boolean finished = dockerClient.execStartCmd(runCmd.getId())
                        .exec(new ExecStartResultCallback(stdout, stderr))
                        .awaitCompletion(timeLimitMs, TimeUnit.MILLISECONDS);
                stopWatch.stop();
                try {
                    peakMemoryBytes = ProcessUtils.readContainerPeakMemory(containerId);
                    log.info("peak_memory: {} MB", peakMemoryBytes / 1024 / 1024);
                } catch (Exception e) {
                    log.error("读取 cgroup 内存峰值失败", e);
                }
                run_time = Math.max(run_time,stopWatch.getLastTaskTimeMillis());
                log.info("run_time: {}", run_time);
                if (!finished) {
                    log.error("用例超时，容器: {},时间:{}", containerId, run_time);
                    errorBuilder.append("TIME_OUT").append("\n");
                    executeResponse.setStatus(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue());
                    break;
                }
                int exitCode = dockerClient.inspectExecCmd(runCmd.getId()).exec().getExitCode();
                String output = stdout.toString(StandardCharsets.UTF_8.name());
                String error = stderr.toString(StandardCharsets.UTF_8.name());
                if (exitCode == 0 ){
                    outputList.add(output);
                }
                // 3. 每个用例跑完读一次容器 cgroup 内存峰值（bytes）作为指标

                if (exitCode != 0) {
                    errorBuilder.append(error).append("\n");
                    // 一个用例内存超限（JVM 堆 OutOfMemoryError，或被 cgroup OOM-kill 退出码 137）
                    // → 整体判内存超限，直接结束。无需区分具体是哪个用例。
                    if (exitCode == 137 || error.contains("OutOfMemoryError")) {
                        log.error("用例{}内存超限（exit={}），整体判内存超限", caseIndex, exitCode);
                        errorBuilder.append("MEMORY_LIMIT_EXCEEDED").append("\n");
                        executeResponse.setStatus(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue());
                    }else{
                        executeResponse.setStatus(JudgeInfoMessageEnum.RUNTIME_ERROR.getValue());
                    }
                    break;
                }
            }
            executeResponse.setTime(run_time);
            executeResponse.setErrorMessage(errorBuilder.length() > 0 ? errorBuilder.toString() : null);
            executeResponse.setOutputList(outputList);
            executeResponse.setMemory(peakMemoryBytes/1024/1024);
            executeResponse.setMessage(errorBuilder.length() <= 0 ? "运行完成" : "程序错误");
            return executeResponse;
        } catch (Exception e) {
            log.error("doExecute 运行异常", e);
            return ExecuteResponse.builder()
                    .status(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue())
                    .errorMessage(e.getMessage()).build();
        } finally {
            ProcessUtils.cleanupContainer(containerId);
        }
    }
    protected boolean deleteFiles (String path) {
        boolean result = FileUtil.del(path);
        return result;
    }

    @Override
    public ExecuteResponse doExecuteCodeSandBoxDocker(ExecuteMessage executeMessage){
        String parentPath = null;   // 代码所在目录（挂载 / 清理用）
        try{
            File writeFile =  saveCodeToFile(executeMessage);
            String filePath = writeFile.getAbsolutePath();   // Main.java 全路径（编译用）
            parentPath = writeFile.getParent();              // 父目录（运行挂载 + 清理用）
            ExecuteResponse compileResponse = buildExecuteResponse(executeMessage, filePath);
            if (compileResponse.getStatus() != null){
                return compileResponse;
            }
            ExecuteResponse executeResponse = executeCode(executeMessage, parentPath);
            boolean isDel = deleteFiles(filePath);
            if (!isDel){
                log.error("deleteFile error,CodeFilePath = {}", filePath);
            }
            return executeResponse;
        }catch (Exception e){
            log.info("程序出错: ",e);
            return ExecuteResponse.builder()
                    .status(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue())
                    .errorMessage(e.getMessage())
                    .build();
        }finally {
            if (parentPath != null && FileUtil.isDirectory(parentPath)) {
                deleteFiles(parentPath);
            }
        }
    }

}

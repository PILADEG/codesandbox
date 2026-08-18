package org.example.codesandbox.utils;

import cn.hutool.core.date.StopWatch;
import cn.hutool.core.io.FileUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
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
import org.springframework.stereotype.Component;
import javax.annotation.Resource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ProcessUtils {

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
    private static final String MAIN = "Main";

    private static DockerClient dockerClient;

    /**
     * 通过 setter 注入静态字段，保持 ProcessUtils 方法可静态调用
     */
    @Resource
    public void setDockerClient(DockerClient dockerClient) {
        ProcessUtils.dockerClient = dockerClient;
    }

    /**
     * 宿主机编译完成后，用 Docker 运行编译产物，一个容器逐个执行所有用例。
     * 输入通过写到挂载目录、容器内用 shell 重定向喂给进程（docker-java 的 exec stdin 通道不可靠）。
     *
     * @param path           编译产物所在宿主机目录（会挂载进容器 /app）
     * @param executeMessage 判题信息，含用例列表和资源限制
     * @return 判题执行结果，outputList 每个元素对应一个用例的输出
     */
    public static ExecuteResponse doExecute(String path, ExecuteMessage executeMessage) {
        String containerId = null;
        ExecuteResponse executeResponse = new ExecuteResponse();
        try {
            ensureImage();

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
                    .withName("sandbox-" + UUID.randomUUID())
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
                    peakMemoryBytes = readContainerPeakMemory(containerId);
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
            cleanupContainer(containerId);
            FileUtil.del(path);
        }
    }

    private static void ensureImage() throws Exception {
        try {
            dockerClient.inspectImageCmd(RUN_IMAGE).exec();
        } catch (NotFoundException e) {
            log.info("镜像 {} 不存在，开始拉取...", RUN_IMAGE);
            dockerClient.pullImageCmd("eclipse-temurin").withTag("8-jdk")
                    .start().awaitCompletion();
        }
    }

    /**
     * 读取容器 cgroup 内存峰值（bytes）。
     * cgroup v2 读 memory.peak，v1 兜底 memory.max_usage_in_bytes。
     * 该值是内核维护的容器生命周期高水位，能准确反映所有用例中的最大内存占用，
     * 与 docker stats 的采样时机无关。
     */
    private static long readContainerPeakMemory(String containerId) throws Exception {
        ExecCreateCmdResponse cmd = dockerClient.execCreateCmd(containerId)
                .withCmd("sh", "-c",
                        "cat /sys/fs/cgroup/memory.peak 2>/dev/null || cat /sys/fs/cgroup/memory/memory.max_usage_in_bytes 2>/dev/null")
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        dockerClient.execStartCmd(cmd.getId())
                .exec(new ExecStartResultCallback(out, err))
                .awaitCompletion(2000, TimeUnit.MILLISECONDS);
        return Long.parseLong(out.toString(StandardCharsets.UTF_8.name()).trim());
    }

    private static void cleanupContainer(String containerId) {
        if (containerId == null) {
            return;
        }
        try {
            dockerClient.killContainerCmd(containerId).exec();
        } catch (Exception ignored) {
        }
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception ignored) {
        }
    }

    public static ExecuteResponse buildExecuteResponse(Process process) {
        try {
            Integer exitCode = process.waitFor();
            if (exitCode == 0){
                log.info("compile success");
                BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                StringBuilder stringBuilder = new StringBuilder();
                String str;
                while ((str = bufferedReader.readLine()) != null) {
                    stringBuilder.append(str);
                }
                log.info("compile output: {}", stringBuilder);
                return ExecuteResponse.builder()
                        .message(stringBuilder.toString())
                        .build();
            }else{
                log.info("compile error");
                BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                StringBuilder stringBuilder = new StringBuilder();
                String str;
                while ((str = bufferedReader.readLine()) != null) {
                    stringBuilder.append(str);
                }
                log.info("compile output: {}", stringBuilder);
                BufferedReader errorReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()));
                StringBuilder errorBuilder = new StringBuilder();
                String errorStr;
                while ((errorStr = errorReader.readLine()) != null) {
                    errorBuilder.append(errorStr);
                }
                log.info("compile error: {}", errorBuilder);
                return ExecuteResponse.builder()
                        .status(JudgeInfoMessageEnum.COMPILE_ERROR.getValue())
                        .errorMessage(errorBuilder.toString())
                        .message(stringBuilder.toString())
                        .build();
            }
        } catch (Exception e) {
            log.error("compileCmd error", e);
            return ExecuteResponse
                    .builder()
                    .status(JudgeInfoMessageEnum.COMPILE_ERROR.getValue())
                    .errorMessage(e.getMessage())
                    .build();
        }
    }
}

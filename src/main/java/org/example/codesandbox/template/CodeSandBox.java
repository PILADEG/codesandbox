package org.example.codesandbox.template;

import org.example.codesandbox.model.judge.execute.ExecuteMessage;
import org.example.codesandbox.model.judge.execute.ExecuteResponse;

public interface CodeSandBox {
    public ExecuteResponse doExecuteCodeSandBoxDocker(ExecuteMessage executeMessage);
}

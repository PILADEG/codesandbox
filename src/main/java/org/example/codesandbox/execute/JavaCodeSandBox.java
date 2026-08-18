package org.example.codesandbox.execute;


import org.example.codesandbox.model.judge.execute.ExecuteMessage;
import org.example.codesandbox.model.judge.execute.ExecuteResponse;
import org.example.codesandbox.template.CodeSandBoxTemplate;
import org.springframework.stereotype.Component;

@Component
public class JavaCodeSandBox extends CodeSandBoxTemplate {
    @Override
    public ExecuteResponse doExecuteCodeSandBoxDocker(ExecuteMessage executeMessage){
        return super.doExecuteCodeSandBoxDocker(executeMessage);
    }
}

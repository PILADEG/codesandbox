package org.example.codesandbox.model.judge.execute;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteResponse implements Serializable {

    private String status;

    private String message;

    private String errorMessage;

    private Long time;

    private Long memory;

    private List<String> outputList;

    private static final long serialVersionUID = 1L;
}

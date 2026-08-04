package com.devmind.document;

import com.devmind.document.dto.DocumentTaskResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class DocumentTaskController {

    private final DocumentTaskService taskService;

    public DocumentTaskController(DocumentTaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/tasks/{taskId}")
    public DocumentTaskResponse task(@PathVariable Long taskId) {
        return taskService.getTask(taskId);
    }

    @GetMapping("/documents/{documentId}/task")
    public DocumentTaskResponse taskByDocument(@PathVariable Long documentId) {
        return taskService.getTaskByDocument(documentId);
    }
}

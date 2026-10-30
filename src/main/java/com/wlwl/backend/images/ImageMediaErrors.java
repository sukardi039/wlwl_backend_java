package com.wlwl.backend.images;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.io.IOException;
import java.nio.file.InvalidPathException;

@RestControllerAdvice(assignableTypes = ImageMediaController.class)
public class ImageMediaErrors extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageMediaErrors.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail rejected(ResponseStatusException e) {
        if (e.getStatusCode().is5xxServerError()) log.error("Image API unavailable: {}", e.getReason());
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), e.getReason());
    }

    @ExceptionHandler({IOException.class, InvalidPathException.class})
    public ProblemDetail storageFailure(Exception e) {
        log.error("Image storage operation failed", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Image storage operation failed");
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail databaseFailure(DataAccessException e) {
        log.error("Image API database operation failed", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Image service temporarily unavailable");
    }
}

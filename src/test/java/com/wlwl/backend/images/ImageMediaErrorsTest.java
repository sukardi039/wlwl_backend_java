package com.wlwl.backend.images;

import com.wlwl.backend.session.RestSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.io.IOException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ImageMediaErrorsTest {

    private ImageMediaService images;
    private RestSessionService sessions;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        images = mock(ImageMediaService.class);
        sessions = mock(RestSessionService.class);
        when(sessions.authenticate("K1")).thenReturn(true);
        mvc = MockMvcBuilders.standaloneSetup(new ImageMediaController(images, sessions))
                .setControllerAdvice(new ImageMediaErrors()).build();
    }

    @Test
    void databaseFailureIs503NotAnInvalidSessionOrEmptySuccess() throws Exception {
        when(sessions.authenticate("K1")).thenThrow(
                new DataAccessResourceFailureException("private database connection details"));
        mvc.perform(request()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("Image service temporarily unavailable"));
        verifyNoInteractions(images);
    }

    @Test
    void storageFailureIs500WithoutExposingFilesystemDetails() throws Exception {
        when(images.store(any())).thenThrow(new IOException("private storage path"));
        mvc.perform(request()).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Image storage operation failed"));
    }

    @Test
    void multipartLimitExceptionUsesProblemJson() throws Exception {
        when(images.store(any())).thenThrow(new MaxUploadSizeExceededException(2097152));
        mvc.perform(request()).andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(413));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request() {
        return multipart("/backend/media/images")
                .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1}))
                .header("Authorization", "Bearer K1");
    }
}

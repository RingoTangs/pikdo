package org.example.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.example.exception.BusinessException;
import org.example.exception.CommonError;
import org.example.exception.ErrorResponse;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("dev")
@RestController
@RequestMapping(value = "/examples", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExampleController {

    @Operation(summary = "正常返回 JSON")
    @ApiResponse(
            responseCode = "200",
            description = "返回示例消息",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = SuccessResponse.class)))
    @GetMapping("/success")
    public SuccessResponse success() {
        return new SuccessResponse("Hello World!");
    }

    @Operation(summary = "业务异常处理示例")
    @ApiResponse(
            responseCode = "404",
            description = "业务异常返回错误码和消息",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/business-error")
    public void businessError() {
        throw BusinessException.of(CommonError.NOT_FOUND);
    }

    @Operation(summary = "未知异常兜底处理示例")
    @ApiResponse(
            responseCode = "500",
            description = "未知异常返回固定错误信息",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/unexpected-error")
    public void unexpectedError() {
        throw new IllegalStateException("Example unexpected error");
    }

    public record SuccessResponse(String message) {}

    @Operation(summary = "JSON 请求体校验示例")
    @ApiResponse(
            responseCode = "200",
            description = "返回校验通过的请求体",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ValidationRequest.class)))
    @ApiResponse(
            responseCode = "400",
            description = "请求参数错误：REQUEST_VALIDATION_FAILED、REQUEST_INVALID_REQUEST_BODY",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @PostMapping(value = "/validation/body", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ValidationRequest validateBody(@Valid @RequestBody ValidationRequest request) {
        return request;
    }

    @Operation(summary = "查询参数校验示例")
    @ApiResponse(
            responseCode = "200",
            description = "返回校验通过的年龄",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = AgeResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description =
                    "请求参数错误：REQUEST_VALIDATION_FAILED、REQUEST_MISSING_PARAMETER、REQUEST_INVALID_PARAMETER_TYPE",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/validation/query")
    public AgeResponse validateQuery(@RequestParam @Min(1) Integer age) {
        return new AgeResponse(age);
    }

    public record ValidationRequest(
            @NotBlank @Nullable String name, @NotNull @Min(1) @Nullable Integer age) {}

    public record AgeResponse(Integer age) {}
}

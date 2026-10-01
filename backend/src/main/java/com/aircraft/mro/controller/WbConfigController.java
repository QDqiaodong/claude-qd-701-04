package com.aircraft.mro.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.dto.WeightDtos;
import com.aircraft.mro.dto.WeightViews;
import com.aircraft.mro.service.WbConfigService;

import jakarta.validation.Valid;

/**
 * 模块八（3/3）：机队基线状态、构型调整链、版本派生与版本对比。
 */
@RestController
@RequestMapping("/api/weight")
public class WbConfigController {

    private final WbConfigService configService;

    public WbConfigController(WbConfigService configService) {
        this.configService = configService;
    }

    /** 兼容飞机台账：无基线飞机照常列出并标“未建立基线”，不猜测默认重心 */
    @GetMapping("/fleet")
    public ApiResponse<List<WeightViews.AircraftWeightStatusView>> fleet() {
        return ApiResponse.success(configService.fleetStatus());
    }

    @GetMapping("/aircraft/{aircraftId}/chain")
    public ApiResponse<WeightViews.ConfigChainView> chain(@PathVariable("aircraftId") Long aircraftId) {
        return ApiResponse.success(configService.chain(aircraftId));
    }

    @GetMapping("/versions/{versionId}")
    public ApiResponse<WeightViews.ConfigVersionView> version(@PathVariable("versionId") Long versionId) {
        return ApiResponse.success(configService.versionDetail(versionId));
    }

    /** 从当前版本派生调整版本（旧版本分叉/跨机并入都在服务端拒绝） */
    @PostMapping("/config/derive")
    public ApiResponse<WeightViews.ConfigVersionView> derive(@Valid @RequestBody WeightDtos.DeriveReq req) {
        return ApiResponse.success("构型调整版本已生成（已冻结上一版本）", configService.derive(req));
    }

    /** 版本对比：默认基线 → 当前版本；越界方向/差值看各版本冻结结果 */
    @PostMapping("/aircraft/{aircraftId}/compare")
    public ApiResponse<WeightViews.VersionCompareView> compare(
            @PathVariable("aircraftId") Long aircraftId,
            @RequestBody(required = false) WeightDtos.CompareReq req) {
        WeightDtos.CompareReq body = req == null ? new WeightDtos.CompareReq() : req;
        return ApiResponse.success(configService.compare(
                aircraftId, body.getFromVersionId(), body.getToVersionId()));
    }
}

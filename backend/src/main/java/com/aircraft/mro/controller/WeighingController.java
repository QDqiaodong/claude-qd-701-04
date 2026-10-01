package com.aircraft.mro.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aircraft.mro.common.ApiResponse;
import com.aircraft.mro.dto.WeightDtos;
import com.aircraft.mro.dto.WeightViews;
import com.aircraft.mro.service.WeighingService;

import jakarta.validation.Valid;

/**
 * 模块八（2/3）：实机称重记录与计算明细。所有重心/包线计算都在服务端，
 * 前端“试算”按钮也只是把表单提交到 /preview，不存在前端算重心的旁路。
 */
@RestController
@RequestMapping("/api/weight/weighings")
public class WeighingController {

    private final WeighingService weighingService;

    public WeighingController(WeighingService weighingService) {
        this.weighingService = weighingService;
    }

    @GetMapping
    public ApiResponse<List<WeightViews.WeighingListItem>> list(@RequestParam(required = false) Long aircraftId) {
        return ApiResponse.success(weighingService.list(aircraftId));
    }

    @GetMapping("/{id}")
    public ApiResponse<WeightViews.WeighingView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(weighingService.detail(id));
    }

    /** 服务端试算（不入库），返回完整计算明细与包线判定 */
    @PostMapping("/preview")
    public ApiResponse<WeightViews.WeighingView> preview(@Valid @RequestBody WeightDtos.PreviewReq req) {
        return ApiResponse.success("试算完成（未保存）", weighingService.preview(req));
    }

    @PostMapping
    public ApiResponse<WeightViews.WeighingView> create(@Valid @RequestBody WeightDtos.WeighingSaveReq req) {
        return ApiResponse.success("称重记录已登记，待两人复核", weighingService.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<WeightViews.WeighingView> update(@PathVariable("id") Long id,
                                                        @Valid @RequestBody WeightDtos.WeighingSaveReq req) {
        return ApiResponse.success("称重记录已更新", weighingService.update(id, req));
    }

    /** 两人复核确认 → 成为该机当前称重基线，并以其为根建立构型链 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<WeightViews.WeighingView> confirm(@PathVariable("id") Long id,
                                                         @Valid @RequestBody WeightDtos.WeighingConfirmReq req) {
        return ApiResponse.success("复核确认完成，已成为当前称重基线", weighingService.confirm(id, req));
    }
}

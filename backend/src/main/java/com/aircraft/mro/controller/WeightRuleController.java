package com.aircraft.mro.controller;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
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
import com.aircraft.mro.service.WeightRuleService;

import jakarta.validation.Valid;

/**
 * 模块八（1/3）：称重规则版本（机型级、可版本化、引用即冻结）。
 */
@RestController
@RequestMapping("/api/weight/rules")
public class WeightRuleController {

    private final WeightRuleService ruleService;

    public WeightRuleController(WeightRuleService ruleService) {
        this.ruleService = ruleService;
    }

    /** 规则版本列表，可按机型过滤 */
    @GetMapping
    public ApiResponse<List<WeightViews.RuleListItem>> list(@RequestParam(required = false) String model) {
        return ApiResponse.success(ruleService.listRules(model));
    }

    @GetMapping("/{id}")
    public ApiResponse<WeightViews.RuleView> detail(@PathVariable("id") Long id) {
        return ApiResponse.success(ruleService.getRule(id));
    }

    /** 预览某机型在指定称重日期将引用哪个生效规则版本 */
    @GetMapping("/resolve")
    public ApiResponse<WeightViews.RuleResolvedView> resolve(
            @RequestParam String model,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(ruleService.resolveView(model, date));
    }

    @PostMapping
    public ApiResponse<WeightViews.RuleView> createDraft(@Valid @RequestBody WeightDtos.RuleSaveReq req) {
        return ApiResponse.success("规则草稿已创建（尚未生效，不影响历史称重）", ruleService.createDraft(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<WeightViews.RuleView> updateDraft(@PathVariable("id") Long id,
                                                         @Valid @RequestBody WeightDtos.RuleSaveReq req) {
        return ApiResponse.success("规则草稿已更新", ruleService.updateDraft(id, req));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<WeightViews.RuleView> publish(@PathVariable("id") Long id,
                                                     @RequestBody(required = false) WeightDtos.RulePublishReq req) {
        return ApiResponse.success("规则已生效，同机型旧版本已冻结保留",
                ruleService.publish(id, req == null ? new WeightDtos.RulePublishReq() : req));
    }
}

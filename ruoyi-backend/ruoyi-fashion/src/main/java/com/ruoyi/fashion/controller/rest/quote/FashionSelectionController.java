package com.ruoyi.fashion.controller.rest.quote;

import com.ruoyi.fashion.configuration.FashionModuleEnabled;

import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.fashion.application.selection.FashionSelectionService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@FashionModuleEnabled
@RestController
@RequestMapping("/fashion/quotes/{quoteId}/selection")
public class FashionSelectionController extends BaseController {
    private final FashionSelectionService service;

    public FashionSelectionController(FashionSelectionService service) {
        this.service = service;
    }

    @PreAuthorize("@ss.hasPermi('fashion:quote:list')")
    @GetMapping
    public AjaxResult workspace(@PathVariable String quoteId) {
        return success(service.workspace(quoteId));
    }

    @PreAuthorize("@ss.hasPermi('fashion:quote:edit')")
    @Log(title = "智能选品组合锁定", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PutMapping("/combinations/{comboId}/locks")
    public AjaxResult updateLocks(
            @PathVariable String quoteId,
            @PathVariable String comboId,
            @RequestBody ComboLockRequest request) {
        return success(service.updateLocks(quoteId, comboId, request.lockedSlots,
                request.quoteRowVersion, request.comboRowVersion, request.visualHash, getUserId()));
    }

    @PreAuthorize("@ss.hasPermi('fashion:quote:edit')")
    @Log(title = "智能选品组合替换", businessType = BusinessType.UPDATE, isSaveRequestData = false)
    @PostMapping("/combinations/{comboId}/replace")
    public AjaxResult replace(
            @PathVariable String quoteId,
            @PathVariable String comboId,
            @RequestBody ComboReplaceRequest request) {
        return success(service.replace(quoteId, comboId, request.toCommand(), getUserId()));
    }

    public record ManualRequest(long quoteRowVersion,String candidateSetHash,String name,
        java.util.List<String> candidateRefs,java.util.Map<String,Integer> quantities){}
    public record RemoveRequest(long quoteRowVersion,long comboRowVersion){}
    @PreAuthorize("@ss.hasPermi('fashion:quote:edit')")
    @PostMapping("/manual")
    @Log(title="智能选品人工组合",businessType=BusinessType.INSERT,isSaveRequestData=false)
    public AjaxResult manual(@PathVariable String quoteId,@RequestBody ManualRequest body){
        return success(service.saveManual(quoteId,body.quoteRowVersion(),body.candidateSetHash(),body.name(),body.candidateRefs(),body.quantities(),getUserId()));
    }
    @PreAuthorize("@ss.hasPermi('fashion:quote:edit')")
    @PostMapping("/combinations/{comboId}/remove")
    @Log(title="智能选品移除组合",businessType=BusinessType.UPDATE,isSaveRequestData=false)
    public AjaxResult remove(@PathVariable String quoteId,@PathVariable String comboId,@RequestBody RemoveRequest body){
        return success(service.removeManual(quoteId,comboId,body.quoteRowVersion(),body.comboRowVersion(),getUserId()));
    }
}

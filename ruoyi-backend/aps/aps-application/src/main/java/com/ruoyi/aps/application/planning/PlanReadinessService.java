package com.ruoyi.aps.application.planning;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.ruoyi.aps.application.foundation.ApsValidationException;
import com.ruoyi.aps.application.foundation.ApsValidationIssue;
import com.ruoyi.aps.application.resource.ResourceAccessScope;
import com.ruoyi.aps.solver.contract.ValidationResult;

/** 业务选择的无副作用预检，与创建请求共用可信编译和独立校验。 */
public final class PlanReadinessService {
    private final SolverInputCompiler compiler;
    private final Clock clock;
    public PlanReadinessService(SolverInputCompiler compiler) { this(compiler, Clock.systemUTC()); }
    public PlanReadinessService(SolverInputCompiler compiler, Clock clock) { this.compiler=compiler;this.clock=clock; }
    public Readiness check(ResourceAccessScope scope, SolverInputCompiler.CompileRequest input) {
        Instant now=Instant.now(clock);
        var request=new SolverInputCompiler.CompileRequest(UUID.randomUUID().toString(),UUID.randomUUID().toString(),
                input.siteCode(),input.workshopIds(),input.orderIds(),now,input.definitionRevision(),input.executionRevision(),
                input.horizonStartAt(),input.detailEndAt(),input.horizonEndAt(),input.planningAnchorAt(),input.timeUnitSeconds(),
                input.displayTimeZone(),input.modelVersion(),input.maxSolveSeconds(),input.randomSeed(),input.solverSearchThreads(),
                input.absoluteGapLimit(),input.relativeGapLimit(),input.sharedBatches(),input.baseVersion());
        try {
            ValidationResult result=compiler.compile(scope,request).readiness();
            List<ApsValidationIssue> issues=result.problems().stream().flatMap(problem -> {
                if(problem.objectRefs().isEmpty()) return java.util.stream.Stream.of(new ApsValidationIssue(
                        problem.reasonCode().name(),"INPUT","",null,problem.detail()));
                return problem.objectRefs().stream().map(ref -> new ApsValidationIssue(problem.reasonCode().name(),
                        ref.objectType(),ref.objectId(),ref.field(),problem.detail()));
            }).toList();
            return new Readiness(result.validationStatus()==ValidationResult.Status.PASS,now,result.inputHash(),issues);
        } catch(ApsValidationException exception) {
            return new Readiness(false,now,null,exception.issues());
        }
    }
    public record Readiness(boolean ready, Instant checkedAt, String inputHash, List<ApsValidationIssue> issues) {
        public Readiness { issues=List.copyOf(issues); }
    }
}

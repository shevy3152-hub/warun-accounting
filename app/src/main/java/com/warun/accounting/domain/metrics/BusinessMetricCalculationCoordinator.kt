package com.warun.accounting.domain.metrics

/**
 * Pure connection boundary between a normalized source snapshot and the calculator.
 *
 * This class deliberately has no Room, Android, Flow, or UI dependency.  Assembly failures are
 * kept separate from calculator failures so callers cannot mistake unavailable data for a zero.
 */
object BusinessMetricCalculationCoordinator {
    fun calculate(snapshot: BusinessMetricSourceSnapshot): BusinessMetricCalculationResult {
        return when (val assembly = BusinessMetricInputAssembler.assemble(snapshot)) {
            is BusinessMetricInputAssemblyResult.Success -> {
                try {
                    BusinessMetricCalculationResult.Success(
                        BusinessMetricCalculator.calculate(assembly.input),
                    )
                } catch (error: ArithmeticException) {
                    BusinessMetricCalculationResult.CalculationFailure(
                        exceptionType = error::class.qualifiedName
                            ?: error::class.simpleName.orEmpty(),
                        message = error.message,
                    )
                }
            }
            is BusinessMetricInputAssemblyResult.InvalidSourceData,
            is BusinessMetricInputAssemblyResult.InconsistentSnapshot,
            is BusinessMetricInputAssemblyResult.Overflow,
            -> BusinessMetricCalculationResult.AssemblyFailure(assembly)
        }
    }
}

sealed interface BusinessMetricCalculationResult {
    data class Success(val report: BusinessMetricReport) : BusinessMetricCalculationResult

    data class AssemblyFailure(
        val result: BusinessMetricInputAssemblyResult,
    ) : BusinessMetricCalculationResult

    data class CalculationFailure(
        val exceptionType: String,
        val message: String?,
    ) : BusinessMetricCalculationResult
}

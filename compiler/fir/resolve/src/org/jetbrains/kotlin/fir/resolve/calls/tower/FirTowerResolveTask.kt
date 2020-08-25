/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.calls.tower

import org.jetbrains.kotlin.fir.asReversedFrozen
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirResolvedQualifier
import org.jetbrains.kotlin.fir.resolve.FirTowerDataContext
import org.jetbrains.kotlin.fir.resolve.calls.*
import org.jetbrains.kotlin.fir.resolve.scope
import org.jetbrains.kotlin.fir.scopes.FirCompositeScope
import org.jetbrains.kotlin.fir.scopes.FirScope
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.fir.types.impl.FirImplicitBuiltinTypeRef
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.calls.tasks.ExplicitReceiverKind
import org.jetbrains.kotlin.resolve.descriptorUtil.HIDES_MEMBERS_NAME_LIST


internal class TowerDataElementsForName(
    name: Name,
    towerDataContext: FirTowerDataContext
) {
    val nonLocalTowerDataElements = towerDataContext.nonLocalTowerDataElements.asReversedFrozen()
    val reversedFilteredLocalScopes by lazy(LazyThreadSafetyMode.NONE) {
        towerDataContext.localScopes.asReversed().withIndex().filter { (_, scope) -> scope.mayContainName(name) }
    }
    val implicitReceivers by lazy(LazyThreadSafetyMode.NONE) {
        nonLocalTowerDataElements.mapIndexedNotNull { index, towerDataElement ->
            towerDataElement.implicitReceiver?.let { receiver -> IndexedValue(index, receiver) }
        }
    }
}

internal abstract class FirBaseTowerResolveTask(
    protected val resolverSession: FirTowerResolverSession,
    protected val towerDataElementsForName: TowerDataElementsForName,
    protected val collector: CandidateCollector,
    protected val candidateFactory: CandidateFactory,
    protected val stubReceiverCandidateFactory: CandidateFactory? = null
) {
    protected val session get() = resolverSession.components.session
    protected val components get() = resolverSession.components

    private val handler: TowerLevelHandler = TowerLevelHandler()

    open fun interceptTowerGroup(towerGroup: TowerGroup) = towerGroup
    open fun onSuccessfulLevel(towerGroup: TowerGroup) {}

    protected suspend inline fun processLevel(
        towerLevel: SessionBasedTowerLevel,
        callInfo: CallInfo,
        group: TowerGroup,
        explicitReceiverKind: ExplicitReceiverKind = ExplicitReceiverKind.NO_EXPLICIT_RECEIVER,
        onEmptyLevel: () -> Unit = {}
    ) {
        if (processLevel(towerLevel, callInfo, group, explicitReceiverKind)) {
            onEmptyLevel()
        }
    }


    protected fun FirScope.toScopeTowerLevel(
        extensionReceiver: ReceiverValue? = null,
        extensionsOnly: Boolean = false,
        includeInnerConstructors: Boolean = true
    ): ScopeTowerLevel = ScopeTowerLevel(
        session, components, this,
        extensionReceiver, extensionsOnly, includeInnerConstructors
    )

    protected fun ReceiverValue.toMemberScopeTowerLevel(
        extensionReceiver: ReceiverValue? = null,
        implicitExtensionInvokeMode: Boolean = false
    ) = MemberScopeTowerLevel(
        session, components, this,
        extensionReceiver, implicitExtensionInvokeMode,
        scopeSession = components.scopeSession
    )


    protected inline fun enumerateTowerLevels(
        parentGroup: TowerGroup = TowerGroup.EmptyRoot,
        onScope: (FirScope, TowerGroup) -> Unit,
        onImplicitReceiver: (ImplicitReceiverValue<*>, TowerGroup) -> Unit,
    ) {
        for ((index, localScope) in towerDataElementsForName.reversedFilteredLocalScopes) {
            onScope(localScope, parentGroup.Local(index))
        }

        for ((depth, lexical) in towerDataElementsForName.nonLocalTowerDataElements.withIndex()) {
            if (!lexical.isLocal && lexical.scope != null) {
                onScope(lexical.scope, parentGroup.NonLocal(depth))
            }

            lexical.implicitReceiver?.let { implicitReceiverValue ->
                onImplicitReceiver(implicitReceiverValue, parentGroup.Implicit(depth))
            }
        }
    }

    /**
     * @return true if level is empty
     */
    private suspend fun processLevel(
        towerLevel: SessionBasedTowerLevel,
        callInfo: CallInfo,
        group: TowerGroup,
        explicitReceiverKind: ExplicitReceiverKind
    ): Boolean {
        val finalGroup = interceptTowerGroup(group)
        resolverSession.manager.requestGroup(finalGroup)


        val result = handler.handleLevel(
            collector,
            candidateFactory,
            stubReceiverCandidateFactory,
            callInfo,
            explicitReceiverKind,
            finalGroup,
            towerLevel
        )
        if (collector.isSuccess()) onSuccessfulLevel(finalGroup)
        return result == ProcessorAction.NONE

    }
}

internal open class FirTowerResolveTask(
    resolverSession: FirTowerResolverSession,
    towerDataElementsForName: TowerDataElementsForName,
    collector: CandidateCollector,
    candidateFactory: CandidateFactory,
    stubReceiverCandidateFactory: CandidateFactory? = null
) : FirBaseTowerResolveTask(
    resolverSession,
    towerDataElementsForName,
    collector,
    candidateFactory,
    stubReceiverCandidateFactory
) {

    suspend fun runResolverForQualifierReceiver(
        info: CallInfo,
        resolvedQualifier: FirResolvedQualifier
    ) {
        val qualifierReceiver = createQualifierReceiver(resolvedQualifier, session, components.scopeSession)

        when {
            info.isPotentialQualifierPart -> {
                processClassifierScope(info, qualifierReceiver, prioritized = true)
                processQualifierScopes(info, qualifierReceiver)
            }
            else -> {
                processQualifierScopes(info, qualifierReceiver)
                processClassifierScope(info, qualifierReceiver, prioritized = false)
            }
        }

        if (resolvedQualifier.symbol != null) {
            val typeRef = resolvedQualifier.typeRef
            // NB: yet built-in Unit is used for "no-value" type
            if (info.callKind == CallKind.CallableReference) {
                if (info.stubReceiver != null || typeRef !is FirImplicitBuiltinTypeRef) {
                    runResolverForExpressionReceiver(info, resolvedQualifier, parentGroup = TowerGroup.QualifierValue)
                }
            } else {
                if (typeRef !is FirImplicitBuiltinTypeRef) {
                    runResolverForExpressionReceiver(info, resolvedQualifier, parentGroup = TowerGroup.QualifierValue)
                }
            }
        }
    }

    private suspend fun processQualifierScopes(
        info: CallInfo, qualifierReceiver: QualifierReceiver?
    ) {
        if (qualifierReceiver == null) return
        val callableScope = qualifierReceiver.callableScope() ?: return
        processLevel(
            callableScope.toScopeTowerLevel(includeInnerConstructors = false),
            info.noStubReceiver(), TowerGroup.Qualifier
        )
    }

    private suspend fun processClassifierScope(
        info: CallInfo, qualifierReceiver: QualifierReceiver?, prioritized: Boolean
    ) {
        if (qualifierReceiver == null) return
        if (info.callKind != CallKind.CallableReference &&
            qualifierReceiver is ClassQualifierReceiver &&
            qualifierReceiver.classSymbol != qualifierReceiver.originalSymbol
        ) return
        val scope = qualifierReceiver.classifierScope() ?: return
        val group = if (prioritized) TowerGroup.ClassifierPrioritized else TowerGroup.Classifier
        processLevel(
            scope.toScopeTowerLevel(includeInnerConstructors = false), info.noStubReceiver(),
            group
        )
    }

    suspend fun runResolverForExpressionReceiver(
        info: CallInfo,
        receiver: FirExpression,
        parentGroup: TowerGroup = TowerGroup.EmptyRoot
    ) {
        val explicitReceiverValue = ExpressionReceiverValue(receiver)

        processExtensionsThatHideMembers(info, explicitReceiverValue, parentGroup)

        // Member scope of expression receiver
        processLevel(
            explicitReceiverValue.toMemberScopeTowerLevel(), info, parentGroup.Member, ExplicitReceiverKind.DISPATCH_RECEIVER
        )

        val shouldProcessExplicitReceiverScopeOnly =
            info.callKind == CallKind.Function && info.explicitReceiver?.typeRef?.coneTypeSafe<ConeIntegerLiteralType>() != null
        if (shouldProcessExplicitReceiverScopeOnly) {
            // Special case (integer literal type)
            return
        }

        enumerateTowerLevels(
            parentGroup = parentGroup,
            onScope = { scope, group ->
                processScopeForExplicitReceiver(
                    scope,
                    explicitReceiverValue,
                    info,
                    group
                )
            },
            onImplicitReceiver = { implicitReceiverValue, group ->
                processCombinationOfReceivers(implicitReceiverValue, explicitReceiverValue, info, group)
            }
        )
    }

    suspend fun runResolverForNoReceiver(
        info: CallInfo
    ) {
        processExtensionsThatHideMembers(info, explicitReceiverValue = null)

        val emptyScopes = mutableSetOf<FirScope>()
        val implicitReceiverValuesWithEmptyScopes = mutableSetOf<ImplicitReceiverValue<*>>()

        enumerateTowerLevels(
            onScope = l@{ scope, group ->
                // NB: this check does not work for variables
                // because we do not search for objects if we have extension receiver
                if (info.callKind != CallKind.VariableAccess && scope in emptyScopes) return@l

                processLevel(
                    scope.toScopeTowerLevel(), info, group,
                    onEmptyLevel = {
                        emptyScopes += scope
                    }
                )
            },
            onImplicitReceiver = { receiver, group ->
                processCandidatesWithGivenImplicitReceiverAsValue(
                    receiver,
                    info,
                    group,
                    implicitReceiverValuesWithEmptyScopes,
                    emptyScopes
                )
            }
        )
    }

    suspend fun runResolverForSuperReceiver(
        info: CallInfo,
        superTypeRef: FirTypeRef
    ) {
        val scope = when (superTypeRef) {
            is FirResolvedTypeRef -> superTypeRef.type.scope(session, components.scopeSession)
            is FirComposedSuperTypeRef -> FirCompositeScope(
                superTypeRef.superTypeRefs.mapNotNull { it.type.scope(session, components.scopeSession) }
            )
            else -> null
        } ?: return
        processLevel(
            scope.toScopeTowerLevel(),
            info, TowerGroup.Member, explicitReceiverKind = ExplicitReceiverKind.DISPATCH_RECEIVER
        )
    }

    private suspend fun processExtensionsThatHideMembers(
        info: CallInfo,
        explicitReceiverValue: ReceiverValue?,
        parentGroup: TowerGroup = TowerGroup.EmptyRoot
    ) {
        val shouldProcessExtensionsBeforeMembers =
            info.callKind == CallKind.Function && info.name in HIDES_MEMBERS_NAME_LIST

        if (!shouldProcessExtensionsBeforeMembers) return

        val importingScopes = components.fileImportsScope.asReversed()
        for ((index, topLevelScope) in importingScopes.withIndex()) {
            if (explicitReceiverValue != null) {
                processHideMembersLevel(
                    explicitReceiverValue, topLevelScope, info, index, depth = null,
                    ExplicitReceiverKind.EXTENSION_RECEIVER, parentGroup
                )
            } else {
                for ((depth, implicitReceiverValue) in towerDataElementsForName.implicitReceivers) {
                    processHideMembersLevel(
                        implicitReceiverValue, topLevelScope, info, index, depth,
                        ExplicitReceiverKind.NO_EXPLICIT_RECEIVER, parentGroup
                    )
                }
            }
        }
    }

    private suspend fun processScopeForExplicitReceiver(
        scope: FirScope,
        explicitReceiverValue: ExpressionReceiverValue,
        info: CallInfo,
        towerGroup: TowerGroup,
    ) {
        processLevel(
            scope.toScopeTowerLevel(extensionReceiver = explicitReceiverValue),
            info, towerGroup, ExplicitReceiverKind.EXTENSION_RECEIVER
        )

    }

    private suspend fun processCombinationOfReceivers(
        implicitReceiverValue: ImplicitReceiverValue<*>,
        explicitReceiverValue: ExpressionReceiverValue,
        info: CallInfo,
        parentGroup: TowerGroup
    ) {
        // Member extensions
        processLevel(
            implicitReceiverValue.toMemberScopeTowerLevel(extensionReceiver = explicitReceiverValue),
            info, parentGroup.Member, ExplicitReceiverKind.EXTENSION_RECEIVER
        )
    }

    private suspend fun processCandidatesWithGivenImplicitReceiverAsValue(
        receiver: ImplicitReceiverValue<*>,
        info: CallInfo,
        parentGroup: TowerGroup,
        implicitReceiverValuesWithEmptyScopes: MutableSet<ImplicitReceiverValue<*>>,
        emptyScopes: MutableSet<FirScope>
    ) {
        processLevel(
            receiver.toMemberScopeTowerLevel(), info, parentGroup.Member,
            onEmptyLevel = {
                implicitReceiverValuesWithEmptyScopes += receiver
            }
        )

        enumerateTowerLevels(
            parentGroup,
            onScope = l@{ scope, group ->
                if (scope in emptyScopes) return@l

                processLevel(
                    scope.toScopeTowerLevel(extensionReceiver = receiver),
                    info, group,
                    onEmptyLevel = {
                        emptyScopes += scope
                    }
                )
            },
            onImplicitReceiver = l@{ implicitReceiverValue, group ->
                if (implicitReceiverValue in implicitReceiverValuesWithEmptyScopes) return@l
                processLevel(
                    implicitReceiverValue.toMemberScopeTowerLevel(extensionReceiver = receiver),
                    info, group
                )
            }
        )

    }

    private suspend fun processHideMembersLevel(
        receiverValue: ReceiverValue,
        topLevelScope: FirScope,
        info: CallInfo,
        index: Int,
        depth: Int?,
        explicitReceiverKind: ExplicitReceiverKind,
        parentGroup: TowerGroup
    ) = processLevel(
        topLevelScope.toScopeTowerLevel(
            extensionReceiver = receiverValue, extensionsOnly = true
        ),
        info,
        parentGroup.TopPrioritized(index).let { if (depth != null) it.Implicit(depth) else it },
        explicitReceiverKind,
    )
}
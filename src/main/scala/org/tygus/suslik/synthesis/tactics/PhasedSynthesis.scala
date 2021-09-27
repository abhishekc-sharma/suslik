package org.tygus.suslik.synthesis.tactics

import org.tygus.suslik.language.Statements._
import org.tygus.suslik.logic.Specifications.Goal
import org.tygus.suslik.synthesis.SearchTree.OrNode
import org.tygus.suslik.synthesis._
import org.tygus.suslik.synthesis.rules.LogicalRules.{FrameUnfolding, FrameUnfoldingFinal}
import org.tygus.suslik.synthesis.rules.Rules.{GeneratesCode, RuleResult, SynthesisRule}
import org.tygus.suslik.synthesis.rules.UnfoldingRules.Close
import org.tygus.suslik.synthesis.rules.UnificationRules.HeapUnifyUnfolding
import org.tygus.suslik.synthesis.rules._
import scala.collection.mutable.ArrayBuffer

class PhasedSynthesis(config: SynConfig) extends Tactic {

  val is_static = true

  def nextRules(node: OrNode): List[SynthesisRule] = {

    val goal = node.goal

    // TODO: maybe I should compute "index" separately for anyPhaseRulesOrSpecBasedRules and sketchHole.
    val index = {
      if (false)
        0
      else
        (   (if (goal.post.isOpAndWithBoolEq) math.pow(2,0) else 0) //for SubstRight
          + (if (goal.post.isForStarPartial) math.pow(2,1) else 0) //for StarPartial
          + (if (goal.hasHeapletPointsGhostInPre) math.pow(2,2) else 0) // for Read
          )
    }

    def anyPhaseRulesOrSpecBasedRules = {

      val ordersOfAnyPhaseOrSpecBased = goal.env.ordersOfAnyPhaseOrSpecBased
      val orderOfAnyPhaseOrSpecBased = ordersOfAnyPhaseOrSpecBased.apply(index.toInt)

      val unOrderedAnyPhaseOrSpecBased = Vector (
        anyPhaseRules(goal),
        specBasedRules(node)
      )

      val anyPhaseRulesOrSpecBasedRulesNested = {
        if (goal.env.config.evolutionary)
          List(
            unOrderedAnyPhaseOrSpecBased(orderOfAnyPhaseOrSpecBased.apply(0)),
            unOrderedAnyPhaseOrSpecBased(orderOfAnyPhaseOrSpecBased.apply(1))
          ) else List(unOrderedAnyPhaseOrSpecBased:_*)
      }

      anyPhaseRulesOrSpecBasedRulesNested.flatten

    }

    def sketchHole = {

      val ordersOfSketchHole = goal.env.ordersOfSketchHole
      val orderOfSketchHole = ordersOfSketchHole.apply(index.toInt)

      val unOrderedSketchHole =
        List (
          anyPhaseRules(goal).filterNot(_.isInstanceOf[GeneratesCode]),
          symbolicExecutionRules(goal),
          specBasedRules(node).filterNot(_.isInstanceOf[GeneratesCode])
        )

      val sketchHoleNested = {
        if (goal.env.config.evolutionary)
          List(
            unOrderedSketchHole(orderOfSketchHole.apply(0)),
            unOrderedSketchHole(orderOfSketchHole.apply(1)),
            unOrderedSketchHole(orderOfSketchHole.apply(2))
          ) else List(unOrderedSketchHole:_*)
      }

      sketchHoleNested.flatten

    }

    if (goal.isUnsolvable) Nil
    else if (goal.sketch != Hole) sketchHole // Until we encounter a hole, symbolically execute the sketch
    else if (goal.callGoal.nonEmpty) callAbductionRules(goal)
    else anyPhaseRulesOrSpecBasedRules
  }

  def filterExpansions(allExpansions: Seq[RuleResult]): Seq[RuleResult] = allExpansions

  protected def specBasedRules(node: OrNode): List[SynthesisRule] = {

    val goal = node.goal

    if (goal.hasPredicates()) {
      // Unfolding phase: get rid of predicates
      val lastUnfoldingRule = node.ruleHistory.dropWhile(anyPhaseRules(goal).contains).headOption
      if (lastUnfoldingRule.contains(HeapUnifyUnfolding) ||
        lastUnfoldingRule.contains(FrameUnfolding) ||
        lastUnfoldingRule.contains(FrameUnfoldingFinal))
        unfoldingNoUnfoldPhaseRules(goal)
      else if (lastUnfoldingRule.contains(Close))
      // Once a rule that works on post was used, only use those
        unfoldingPostPhaseRules(node)
      else unfoldingPhaseRules(node)
    } else if (goal.post.hasBlocks) {
      // Block phase: get rid of blocks
      postBlockPhaseRules(goal)
    } else if (goal.hasBlocks) {
      preBlockPhaseRules
    } else if (goal.hasExistentialPointers) {
      // Pointer phase: match all existential pointers
      pointerPhaseRules(goal)
    } else {
      // Pure phase: get rid of all the heap
      purePhaseRules(goal)
    }
  }

  protected def anyPhaseRules(goal:Goal): List[SynthesisRule] = {

    val index = {
      if (false)
        0
      else
        (   (if (goal.post.isOpAndWithBoolEq) math.pow(2,0) else 0) //for SubstRight
          + (if (goal.post.isForStarPartial) math.pow(2,1) else 0) //for StarPartial
          + (if (goal.hasHeapletPointsGhostInPre) math.pow(2,2) else 0) // for Read
          )
    }

    val ordersOfAnyPhaseRules = goal.env.ordersOfAnyPhaseRules
    val orderOfAnyPhaseRules = ordersOfAnyPhaseRules.apply(index.toInt)

    val unorderedAnyPhaseRules = Vector (
      LogicalRules.StarPartial,
      LogicalRules.NilNotLval,
      LogicalRules.Inconsistency,
      FailRules.PostInconsistent,
      LogicalRules.SubstLeft,
      UnificationRules.SubstRight,
      //    LogicalRules.WeakenPre,
      OperationalRules.ReadRule,
      BranchRules.Branch
    )

    if (goal.env.config.evolutionary) {
      List(
        unorderedAnyPhaseRules(orderOfAnyPhaseRules.apply(0)),
        unorderedAnyPhaseRules(orderOfAnyPhaseRules.apply(1)),
        unorderedAnyPhaseRules(orderOfAnyPhaseRules.apply(2)),
        unorderedAnyPhaseRules(orderOfAnyPhaseRules.apply(3)),
        unorderedAnyPhaseRules(orderOfAnyPhaseRules.apply(4)),
        unorderedAnyPhaseRules(orderOfAnyPhaseRules.apply(5)),
        unorderedAnyPhaseRules(orderOfAnyPhaseRules.apply(6)),
        unorderedAnyPhaseRules(orderOfAnyPhaseRules.apply(7))
      )
    } else
      List(unorderedAnyPhaseRules:_*)
  }

  protected def symbolicExecutionRules(goal:Goal): List[SynthesisRule] = {

    val index = {
      if (is_static)
        0
      else
        (if (goal.isUnsolvable) math.pow(2,0) else 0
          + (if (goal.sketch != Hole) math.pow(2,1) else 0)
          + (if (goal.callGoal.nonEmpty) math.pow(2,2) else 0)
          + (if (goal.hasPredicates()) math.pow(2,3) else 0)
          + (if (goal.post.hasBlocks) math.pow(2,4) else 0)
          + (if (goal.hasBlocks) math.pow(2,5) else 0)
          + (if (goal.hasExistentialPointers) math.pow(2,6) else 0))
    }

    val ordersOfSymbolicExecutionRules = goal.env.ordersOfSymbolicExecutionRules
    val orderOfSymbolicExecutionRules = ordersOfSymbolicExecutionRules.apply(index.toInt)

    val unOrderedSymbolicExecutionRules =
      Vector(
        SymbolicExecutionRules.Open,
        SymbolicExecutionRules.GuidedRead,
        SymbolicExecutionRules.GuidedWrite,
        SymbolicExecutionRules.GuidedAlloc,
        SymbolicExecutionRules.GuidedFree,
        SymbolicExecutionRules.Conditional,
        //    SymbolicExecutionRules.GuidedCall, // TODO: Fix this later with new call rule
      )

    if (goal.env.config.evolutionary) {
      List(
        unOrderedSymbolicExecutionRules(orderOfSymbolicExecutionRules.apply(0)),
        unOrderedSymbolicExecutionRules(orderOfSymbolicExecutionRules.apply(1)),
        unOrderedSymbolicExecutionRules(orderOfSymbolicExecutionRules.apply(2)),
        unOrderedSymbolicExecutionRules(orderOfSymbolicExecutionRules.apply(3)),
        unOrderedSymbolicExecutionRules(orderOfSymbolicExecutionRules.apply(4)),
        unOrderedSymbolicExecutionRules(orderOfSymbolicExecutionRules.apply(5))
      )
    } else
      List(unOrderedSymbolicExecutionRules:_*)
  }

  protected def unfoldingPhaseRules(node:OrNode): List[SynthesisRule] = {

    val goal = node.goal
    val index = {
      if (false)
        0
      else {
        (if (node.hasBeenApplied(SymbolicExecutionRules.Open)) math.pow(2,0) else 0) // UnfoldingRules.Close
      }
    }

    val ordersOfUnfoldingPhaseRules = goal.env.ordersOfUnfoldingPhaseRules
    val orderOfUnfoldingPhaseRules = ordersOfUnfoldingPhaseRules.apply(index.toInt)

    val unOrderedUnfoldingPhaseRules =
      Vector(
        LogicalRules.FrameUnfolding,
        UnificationRules.HeapUnifyUnfolding,
        UnfoldingRules.AbduceCall,
        UnfoldingRules.Open,
        UnfoldingRules.Close,
        //    UnfoldingRules.AbduceCall, // HERE: move AbduceCall here to achieve old behavior
      )

    if (goal.env.config.evolutionary) {
      List(
        unOrderedUnfoldingPhaseRules(orderOfUnfoldingPhaseRules.apply(0)),
        unOrderedUnfoldingPhaseRules(orderOfUnfoldingPhaseRules.apply(1)),
        unOrderedUnfoldingPhaseRules(orderOfUnfoldingPhaseRules.apply(2)),
        unOrderedUnfoldingPhaseRules(orderOfUnfoldingPhaseRules.apply(3)),
        unOrderedUnfoldingPhaseRules(orderOfUnfoldingPhaseRules.apply(4))
      )
    } else
      List(unOrderedUnfoldingPhaseRules:_*)
  }

  protected def unfoldingPostPhaseRules(node: OrNode): List[SynthesisRule] = {

    val goal = node.goal
    val index = {
      if (false)
        0
      else {
        (if (node.hasBeenApplied(SymbolicExecutionRules.Open)) math.pow(2,0) else 0) // for UnfoldingRules.Close
      }
    }

    val ordersOfUnfoldingPostPhaseRules = goal.env.ordersOfUnfoldingPostPhaseRules
    val orderOfUnfoldingPostPhaseRules = ordersOfUnfoldingPostPhaseRules.apply(index.toInt)

    val unOrderedUnfoldingPostPhaseRule =
      List(
        LogicalRules.FrameUnfoldingFinal,
        UnificationRules.HeapUnifyUnfolding,
        UnfoldingRules.Close,
      )

    if (goal.env.config.evolutionary) {
      List(
        unOrderedUnfoldingPostPhaseRule(orderOfUnfoldingPostPhaseRules.apply(0)),
        unOrderedUnfoldingPostPhaseRule(orderOfUnfoldingPostPhaseRules.apply(1)),
        unOrderedUnfoldingPostPhaseRule(orderOfUnfoldingPostPhaseRules.apply(2))
      )
    } else
      List(unOrderedUnfoldingPostPhaseRule:_*)

  }

  protected def unfoldingNoUnfoldPhaseRules(goal: Goal): List[SynthesisRule] = {

    val index = {
      if (is_static)
        0
      else
        (if (goal.isUnsolvable) math.pow(2,0) else 0
          + (if (goal.sketch != Hole) math.pow(2,1) else 0)
          + (if (goal.callGoal.nonEmpty) math.pow(2,2) else 0)
          + (if (goal.hasPredicates()) math.pow(2,3) else 0)
          + (if (goal.post.hasBlocks) math.pow(2,4) else 0)
          + (if (goal.hasBlocks) math.pow(2,5) else 0)
          + (if (goal.hasExistentialPointers) math.pow(2,6) else 0))
    }

    val ordersOfUnfoldingNoUnfoldPhaseRules = goal.env.ordersOfUnfoldingNoUnfoldPhaseRules
    val orderOfUnfoldingNoUnfoldPhaseRules = ordersOfUnfoldingNoUnfoldPhaseRules.apply(index.toInt)

    val unOrderedUnfoldingNoUnfoldPhaseRules =
      Vector (
        LogicalRules.FrameUnfoldingFinal,
        UnificationRules.HeapUnifyUnfolding
      )

    if (goal.env.config.evolutionary)
      List(
        unOrderedUnfoldingNoUnfoldPhaseRules(orderOfUnfoldingNoUnfoldPhaseRules.apply(0)),
        unOrderedUnfoldingNoUnfoldPhaseRules(orderOfUnfoldingNoUnfoldPhaseRules.apply(1))
      )
    else
      List(unOrderedUnfoldingNoUnfoldPhaseRules:_*)

  }

  protected def callAbductionRules(goal: Goal): List[SynthesisRule] = {

    val index = {
      if (false)
        0
      else
        (  (if (goal.post.isOpAndWithBoolEq) math.pow(2,0) else 0) //for SubstRight
         + (if (goal.pre.hasOpAnd && goal.post.hasOpAnd) math.pow(2,1) else 0) // For HeapUnifyPure
          )
    }

    val ordersOfCallAbductionRules = goal.env.ordersOfCallAbductionRules
    val orderOfCallAbductionRules = ordersOfCallAbductionRules.apply(index.toInt)

    val unOrderedCallAbductionRulesNested: Vector[List[SynthesisRule]]  =
      Vector(
        //LogicalRules.SubstLeft,
        List (UnificationRules.SubstRight),
        List (FailRules.PostInconsistent),
        List(FailRules.CheckPost),
        (if (goal.post.sigma.apps.nonEmpty)
          List(
            LogicalRules.FrameUnfoldingFinal,
            UnificationRules.HeapUnifyUnfolding)
        else if (goal.post.sigma.blocks.nonEmpty)
          List(
            LogicalRules.FrameBlock,
            UnificationRules.HeapUnifyBlock,
            //          OperationalRules.AllocRule
          )
        else if (goal.hasExistentialPointers)
          List(
            LogicalRules.FrameFlat,
            //          OperationalRules.WriteRule,
            UnificationRules.HeapUnifyPointer)
        else
          List(
            UnfoldingRules.CallRule,
            UnificationRules.SubstRight,
            LogicalRules.FrameFlat,
            //          OperationalRules.WriteRule,
            UnificationRules.PickArg,
            UnificationRules.PickCard,
            LogicalRules.GhostWrite,
            UnificationRules.HeapUnifyPure,
            LogicalRules.SimplifyConditional,
            OperationalRules.WriteRule,
            //          DelegatePureSynthesis.PureSynthesisNonfinal
            UnificationRules.Pick
          )
          )
      )

    val orderedCallAbductionRulesNested =
      if (goal.env.config.evolutionary)
        List(
          unOrderedCallAbductionRulesNested(orderOfCallAbductionRules.apply(0)),
          unOrderedCallAbductionRulesNested(orderOfCallAbductionRules.apply(1)),
          unOrderedCallAbductionRulesNested(orderOfCallAbductionRules.apply(2)),
          unOrderedCallAbductionRulesNested(orderOfCallAbductionRules.apply(3))
        )
      else
        List(unOrderedCallAbductionRulesNested:_*)

    orderedCallAbductionRulesNested.flatten
  }

  protected def postBlockPhaseRules(goal: Goal): List[SynthesisRule] = {

    val index = {
      if (is_static)
        0
      else
        (if (goal.isUnsolvable) math.pow(2,0) else 0
          + (if (goal.sketch != Hole) math.pow(2,1) else 0)
          + (if (goal.callGoal.nonEmpty) math.pow(2,2) else 0)
          + (if (goal.hasPredicates()) math.pow(2,3) else 0)
          + (if (goal.post.hasBlocks) math.pow(2,4) else 0)
          + (if (goal.hasBlocks) math.pow(2,5) else 0)
          + (if (goal.hasExistentialPointers) math.pow(2,6) else 0))
    }

    val ordersOfPostBlockPhaseRules = goal.env.ordersOfPostBlockPhaseRules
    val orderOfPostBlockPhaseRules = ordersOfPostBlockPhaseRules.apply(index.toInt)

    val unOrdersOfPostBlockPhaseRules =
      Vector(
        (if (config.branchAbduction) BranchRules.AbduceBranch else FailRules.CheckPost),
        LogicalRules.FrameBlock,
        UnificationRules.HeapUnifyBlock,
        OperationalRules.AllocRule
      )

    if (goal.env.config.evolutionary)
      List(
        unOrdersOfPostBlockPhaseRules(orderOfPostBlockPhaseRules.apply(0)),
        unOrdersOfPostBlockPhaseRules(orderOfPostBlockPhaseRules.apply(1)),
        unOrdersOfPostBlockPhaseRules(orderOfPostBlockPhaseRules.apply(2)),
        unOrdersOfPostBlockPhaseRules(orderOfPostBlockPhaseRules.apply(3))
      )
    else List(unOrdersOfPostBlockPhaseRules:_*)
  }

  protected def preBlockPhaseRules: List[SynthesisRule] = List(
    OperationalRules.FreeRule
  )

  protected def pointerPhaseRules(goal:Goal): List[SynthesisRule] = {

    val index = {
      if (is_static)
        0
      else
        (if (goal.isUnsolvable) math.pow(2,0) else 0
          + (if (goal.sketch != Hole) math.pow(2,1) else 0)
          + (if (goal.callGoal.nonEmpty) math.pow(2,2) else 0)
          + (if (goal.hasPredicates()) math.pow(2,3) else 0)
          + (if (goal.post.hasBlocks) math.pow(2,4) else 0)
          + (if (goal.hasBlocks) math.pow(2,5) else 0)
          + (if (goal.hasExistentialPointers) math.pow(2,6) else 0))
    }

    val ordersOfPointerPhaseRules = goal.env.ordersOfPointerPhaseRules
    val orderOfPointerPhaseRules = ordersOfPointerPhaseRules.apply(index.toInt)

    val unOrderedPointerPhaseRules =
      Vector(
        if (config.branchAbduction) BranchRules.AbduceBranch else FailRules.CheckPost,
        //    LogicalRules.SubstLeft,
        //    UnificationRules.SubstRight,
        FailRules.HeapUnreachable,
        LogicalRules.FrameFlat,
        UnificationRules.HeapUnifyPointer,
      )

    if (goal.env.config.evolutionary)
      List(
        unOrderedPointerPhaseRules(orderOfPointerPhaseRules.apply(0)),
        unOrderedPointerPhaseRules(orderOfPointerPhaseRules.apply(1)),
        unOrderedPointerPhaseRules(orderOfPointerPhaseRules.apply(2)),
        unOrderedPointerPhaseRules(orderOfPointerPhaseRules.apply(3))
      )
    else List(unOrderedPointerPhaseRules:_*)

  }

  protected def purePhaseRules(goal: Goal): List[SynthesisRule] = {

    val unorderedPurePhaseRules =
      Vector(
        if (config.branchAbduction) BranchRules.AbduceBranch else FailRules.CheckPost,
        LogicalRules.EmpRule,
        //      LogicalRules.SubstLeft,
        //      UnificationRules.SubstRight,
        FailRules.HeapUnreachable,
        LogicalRules.FrameFlat,
        UnificationRules.PickCard,
        LogicalRules.GhostWrite,
        UnificationRules.HeapUnifyPure,
        LogicalRules.SimplifyConditional,
        OperationalRules.WriteRule,
        if (config.delegatePure) DelegatePureSynthesis.PureSynthesisFinal else UnificationRules.Pick)
    //    ++
    //    (if (config.branchAbduction) List(UnificationRules.Pick) else List())

    val index = {
      if (false)
        0
      else {
        (if (goal.pre.hasOpAnd && goal.post.hasOpAnd) math.pow(2,0) else 0) // For HeapUnifyPure
      }
    }

    val ordersOfPurePhaseRules = goal.env.ordersOfPurePhaseRules
    val orderOfPurePhaseRules = ordersOfPurePhaseRules.apply(index.toInt)

    if (goal.env.config.evolutionary) {
      List(
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(0)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(1)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(2)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(3)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(4)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(5)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(6)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(7)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(8)),
        unorderedPurePhaseRules(orderOfPurePhaseRules.apply(9))
      )
    } else
      List(unorderedPurePhaseRules:_*)

  }

}
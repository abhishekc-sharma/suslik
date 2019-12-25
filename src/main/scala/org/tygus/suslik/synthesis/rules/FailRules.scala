package org.tygus.suslik.synthesis.rules

import org.tygus.suslik.language.Expressions.{Expr, Var}
import org.tygus.suslik.language.IntType
import org.tygus.suslik.language.Statements.Guarded
import org.tygus.suslik.logic.Specifications._
import org.tygus.suslik.logic.smt.SMTSolving
import org.tygus.suslik.logic.{Heaplet, PointsTo, PureLogicUtils, SepLogicUtils}
import org.tygus.suslik.synthesis.rules.Rules._
import org.tygus.suslik.synthesis.rules.OperationalRules.{AllocRule, FreeRule}

/**
  * Rules for short-circuiting failure;
  * do not affect completeness, they are simply an optimization.
  * @author Nadia Polikarpova, Ilya Sergey
  */

object FailRules extends PureLogicUtils with SepLogicUtils with RuleUtils {

  val exceptionQualifier: String = "rule-fail"

  // Noop rule: never applies (used to replace disabled rules)
  object Noop extends SynthesisRule {
    override def toString: String = "Noop"

    def apply(goal: Goal): Seq[RuleResult] = Nil
  }

  // Short-circuits failure if pure post is inconsistent with the pre
  object PostInconsistent extends SynthesisRule with InvertibleRule {
    override def toString: String = "PostInconsistent"

    def apply(goal: Goal): Seq[RuleResult] = {
      val pre = goal.pre.phi
      val post = goal.post.phi

      if (!SMTSolving.sat(pre && post))
        // post inconsistent with pre
        List(RuleResult(List(goal.unsolvableChild), idProducer, goal.allHeaplets, this))
      else
        Nil
    }
  }

  // Short-circuits failure if universal part of post is too strong
  object PostInvalid extends SynthesisRule with InvertibleRule {
    override def toString: String = "PostInvalid"

    def apply(goal: Goal): Seq[RuleResult] = {
      // If precondition does not contain predicates, we can't get get new facts from anywhere
      if (!SMTSolving.valid(goal.pre.phi ==> goal.universalPost))
        // universal post not implies by pre
        List(RuleResult(List(goal.unsolvableChild), idProducer, goal.allHeaplets, this))
      else
        Nil
    }
  }

  object AbduceBranch extends SynthesisRule with InvertibleRule {
    override def toString: String = "AbduceBranch"

    def atomCandidates(goal: Goal): Seq[Expr] =
      for {
        lhs <- goal.programVars
        rhs <- goal.programVars
        if lhs != rhs
        if goal.getType(lhs) == IntType && goal.getType(rhs) == IntType
      } yield lhs |<=| rhs

    def condCandidates(goal: Goal): Seq[Expr] = {
      val atoms = atomCandidates(goal)
      // Toggle this to enable abduction of conjunctions
      // (without branch pruning, produces too many branches)
//      atoms
      for {
        subset <- atoms.toSet.subsets.toSeq
        if subset.nonEmpty
      } yield simplify(mkConjunction(subset.toList))
    }

    /**
      * Find the earliest ancestor of goal
      * that is still valid and has all variables from vars
      */
    def findBranchPoint(vars: Set[Var], goal: Goal, valid: Boolean): Option[Goal] = goal.parent match {
      case None => if (valid) Some(goal) else None // goal is root: return itself if valid
      case Some(pGoal) =>
        if (vars.subsetOf(pGoal.programVars.toSet)) {
          // Parent goal has all variables from vars: recurse
          val pCons = SMTSolving.valid(pGoal.pre.phi ==> pGoal.universalPost)
          findBranchPoint(vars, pGoal, pCons)
        } else if (valid) Some(goal) else None // one of vars undefined in the goal: return itself if valid
    }

    def guardedCandidates(goal: Goal): Seq[RuleResult] =
      for {
        cond <- condCandidates(goal)
        pre = goal.pre.phi
        if SMTSolving.valid((pre && cond) ==> goal.universalPost)
        if SMTSolving.sat(pre && cond)
        bGoal <- findBranchPoint(cond.vars, goal, false)
        thenGoal = goal.spawnChild(goal.pre.copy(phi = goal.pre.phi && cond), childId = Some(0))
        elseGoal = goal.spawnChild(
          pre = bGoal.pre.copy(phi = bGoal.pre.phi && cond.not),
          post = bGoal.post,
          gamma = bGoal.gamma,
          programVars = bGoal.programVars,
          childId = Some(1))
      } yield RuleResult(List(thenGoal, elseGoal),
        StmtProducer(2, liftToSolutions(stmts => Guarded(cond, stmts.head, stmts.last, bGoal.label))),
        goal.allHeaplets,
        this)

    def apply(goal: Goal): Seq[RuleResult] = {
      if (SMTSolving.valid(goal.pre.phi ==> goal.universalPost))
        Nil // valid so far, nothing to say
      else {
        val guarded = guardedCandidates(goal)
        if (guarded.isEmpty)
          // Abduction failed
          if (goal.env.config.fail) List(RuleResult(List(goal.unsolvableChild), idProducer, goal.allHeaplets, this)) // pre doesn't imply post: goal is unsolvable
          else Nil // fail optimization is disabled, so pretend this rule doesn't apply
        else guarded
      }
    }
  }


  // Short-circuits failure if spatial post doesn't match pre
  // This rule is only applicable when only points-to heaplets are left
  object HeapUnreachable extends SynthesisRule with InvertibleRule {
    override def toString: String = "HeapUnreachable"

    // How many chunks there are with each offset?
    def profile(chunks: List[Heaplet]): Map[Int, Int] = chunks.groupBy{ case PointsTo(_,o,_) => o }.mapValues(_.length)

    def apply(goal: Goal): Seq[RuleResult] = {
      assert(!goal.hasPredicates && !goal.hasBlocks) // only points-to left
      val preChunks = goal.pre.sigma.chunks
      val postChunks = goal.post.sigma.chunks

      if ((profile(preChunks) == profile(postChunks)) && // profiles must match
        postChunks.forall{ case pts@PointsTo(v@Var(_),_,_) => goal.isExistential(v) || // each post heaplet is either existential pointer
          findHeaplet(sameLhs(pts), goal.pre.sigma).isDefined }) // or has a heaplet in pre with the same LHS
        Nil
      else
        List(RuleResult(List(goal.unsolvableChild), idProducer, goal.allHeaplets, this)) // spatial parts do not match: only magic can save us
    }
  }
}

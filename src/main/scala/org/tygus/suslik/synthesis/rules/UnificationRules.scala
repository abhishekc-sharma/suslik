package org.tygus.suslik.synthesis.rules

import org.tygus.suslik.language.CardType
import org.tygus.suslik.language.Expressions._
import org.tygus.suslik.logic.Specifications._
import org.tygus.suslik.logic._
import org.tygus.suslik.logic.unification.{PureUnification, SpatialUnification}
import org.tygus.suslik.synthesis._
import org.tygus.suslik.synthesis.rules.Rules._

/**
  * The goal of unification rules is to eliminate existentials
  * via either heap unification or various forms of pure synthesis.
  *
  * @author Nadia Polikarpova, Ilya Sergey
  */

object UnificationRules extends PureLogicUtils with SepLogicUtils with RuleUtils {

  val exceptionQualifier: String = "rule-unification"

  abstract class HeapUnifyOld extends SynthesisRule {
    def heapletFilter(h: Heaplet): Boolean

    def apply(goal: Goal): Seq[RuleResult] = {
      val pre = goal.pre
      val post = goal.post

      val postCandidates = post.sigma.chunks.filter(p => p.vars.exists(goal.isExistential) && heapletFilter(p))

      val alternatives = for {
        s <- postCandidates
        t <- pre.sigma.chunks
        sub <- SpatialUnification.tryUnify(t, s, goal.universals)
        if sub.nonEmpty
        newPostSigma = post.sigma.subst(sub)
        if newPostSigma.chunks.distinct.size == newPostSigma.chunks.size // discard substituion if is produces duplicate chunks in the post
      } yield {
        val newPost = Assertion(post.phi.subst(sub), newPostSigma)
        val newCallGoal = goal.callGoal.map(_.updateSubstitution(sub))
        val newGoal = goal.spawnChild(post = newPost, callGoal = newCallGoal)
        val kont = IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
        RuleResult(List(newGoal), kont, this, goal)
      }
      val derivations = nubBy[RuleResult, Assertion](alternatives, sub => sub.subgoals.head.post)
      derivations.sortBy(s => -s.subgoals.head.similarity)
    }
  }

  abstract class HeapUnify extends SynthesisRule {
    def heapletFilter(h: Heaplet): Boolean

    // Do we have a chance to get rid of the relevant kind of heaplets by only unification and framing?
    def profilesMatch(pre: SFormula, post: SFormula, exact: Boolean): Boolean

    def apply(goal: Goal): Seq[RuleResult] = {
      val pre = goal.pre
      val post = goal.post
      if (!profilesMatch(pre.sigma, post.sigma, goal.callGoal.isEmpty)) return Nil

      val postCandidates = post.sigma.chunks.filter(p => p.vars.exists(goal.isExistential) && heapletFilter(p))

      val alternatives = for {
        s <- postCandidates
        t <- pre.sigma.chunks
        sub <- t.unify(s)
        subExpr = goal.substToFormula(sub)
        newPostSigma = (post.sigma - s) ** t.copyTag(s)
//        (varSub, subExpr) = goal.splitSubst(sub)
//        newPostSigma = (post.sigma - s).subst(varSub) ** t.copyTag(s)
        if newPostSigma.chunks.distinct.size == newPostSigma.chunks.size // discard substitution if is produces duplicate chunks in the post
      } yield {
//        val newPost = Assertion((post.phi && subExpr).subst(varSub), newPostSigma)
//        val newCallGoal = goal.callGoal.map(_.updateSubstitution(varSub))
//        val newGoal = goal.spawnChild(post = newPost, callGoal = newCallGoal)
        val newPost = Assertion(post.phi && subExpr, newPostSigma)
        val newGoal = goal.spawnChild(post = newPost)
        val kont = IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
        RuleResult(List(newGoal), kont, this, goal)
      }
      nubBy[RuleResult, Assertion](alternatives, sub => sub.subgoals.head.post)
//      val derivations = nubBy[RuleResult, Assertion](alternatives, sub => sub.subgoals.head.post)
//      derivations.sortBy(s => -s.subgoals.head.similarity)
    }
  }


  object HeapUnifyUnfolding extends HeapUnify with UnfoldingPhase {
    override def toString: String = "HeapUnifyUnfold"
  }

  object HeapUnifyBlock extends HeapUnify with BlockPhase {
    override def toString: String = "HeapUnifyBlock"
  }

  object HeapUnifyPointer extends HeapUnify with FlatPhase with InvertibleRule {
    override def toString: String = "HeapUnifyPointer"

    override def apply(goal: Goal): Seq[RuleResult] = {
      val pre = goal.pre
      val post = goal.post
      val prePtss = pre.sigma.ptss
      val postPtss = post.sigma.ptss

      def lcpLen(s1: String, s2: String): Int = s1.zip(s2).takeWhile(Function.tupled(_ == _)).length

      val alternatives = for {
        PointsTo(y, oy, _) <- postPtss
        if y.vars.exists(goal.isExistential)
        t@PointsTo(x, ox, _) <- prePtss
        if ox == oy
        if !postPtss.exists(sameLhs(t))
      } yield (y -> x)

      alternatives.sortBy{ case (e1, e2) => -lcpLen(e1.pp, e2.pp) }.headOption match {
        case None => Nil
        case Some((y, x)) => {
          val subExpr = goal.substToFormula(Map(y -> x))
          val newPost = Assertion(post.phi && subExpr, post.sigma)
          val newGoal = goal.spawnChild(post = newPost)
          val kont = IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
          List(RuleResult(List(newGoal), kont, this, goal))
        }
      }
    }
  }

  object HeapUnifyPure extends HeapUnify with FlatPhase {
    override def toString: String = "HeapUnifyPure"
  }

  /*
    X ∈ GV(post) / GV (pre)
    Γ ; {φ ; P} ; {[l/X]ψ ; [l/X]Q} ---> S
    --------------------------------------- [subst-R]
    Γ ; {φ ; P} ; {ψ ∧ X = l; Q} ---> S
  */
  object SubstRight extends SynthesisRule with InvertibleRule {
    override def toString: String = "SubstR"

    def apply(goal: Goal): Seq[RuleResult] = {
      val p2 = goal.post.phi
      val s2 = goal.post.sigma

      def isExsistVar(e: Expr) = e.isInstanceOf[Var] && goal.isExistential(e.asInstanceOf[Var])

      findConjunctAndRest({
        case BinaryExpr(OpEq, l, r) => (isExsistVar(l) || isExsistVar(r)) && l.vars.intersect(r.vars).isEmpty
        case BinaryExpr(OpBoolEq, l, r) => (isExsistVar(l) || isExsistVar(r)) && l.vars.intersect(r.vars).isEmpty
        case BinaryExpr(OpSetEq, l, r) => (isExsistVar(l) || isExsistVar(r)) && l.vars.intersect(r.vars).isEmpty
        case _ => false
      }, p2) match {
        case Some((BinaryExpr(_, l, r), rest2)) =>
          val (x, e) = if (isExsistVar(l)) {
            (l.asInstanceOf[Var], r)
          } else {
            (r.asInstanceOf[Var], l)
          }
          val sigma = Map(x -> e)
          val _p2 = rest2.subst(sigma)
          val _s2 = s2.subst(sigma)
          val newCallGoal = goal.callGoal.map(_.updateSubstitution(sigma))
          val newGoal = goal.spawnChild(post = Assertion(_p2, _s2), callGoal = newCallGoal)
          val kont = IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
          List(RuleResult(List(newGoal), kont, this, goal))
        case _ => Nil
      }
    }
  }

  /*
     Γ ; {φ ∧ φ1 ; P} ; {ψ' ; Q'} ---> S
             s = unify(φ1, φ2)
       {ψ' ; Q'} = subst({ψ ; Q}, s)
   --------------------------------------- [Pure-Unify]
   Γ ; {φ ∧ φ1 ; P} ; {ψ ∧ φ2 ; Q} ---> S

    */

  object PureUnify extends SynthesisRule {
    override def toString: String = "PureUnify"

    def apply(goal: Goal): Seq[RuleResult] = {
      // get post conjuncts with existentials
      val postConjuncts = goal.post.phi.conjuncts.filter(p => p.vars.exists(goal.isExistential) && p.allowUnify).toList
      val preConjuncts = goal.pre.phi.conjuncts.filter(p => p.allowUnify).toList

      for {
        s <- postConjuncts
        t <- preConjuncts
        sigma <- PureUnification.tryUnify(t, s, goal.existentials)
        newGoal = goal.spawnChild(post = goal.post.subst(sigma))
        kont = IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
      } yield RuleResult(List(newGoal), kont, this, goal)
    }
  }

  /*
      Γ ; {φ ; x.f -> l * P} ; {ψ ∧ Y = l ; x.f -> Y * Q} ---> S
      ---------------------------------------------------------- [pick]
      Γ ; {φ ; x.f -> l * P} ; {ψ ; x.f -> Y * Q} ---> S
   */

  object Pick extends SynthesisRule {
    override def toString: String = "Pick"

    def apply(goal: Goal): Seq[RuleResult] = {
      val constants = List(IntConst(0), SetLiteral(List()), BoolConst(true), BoolConst(false))

      for {
        ex <- least(goal.existentials) // since all existentials must go, no point trying them in different order
        v <- toSorted(goal.allUniversals.intersect(goal.pre.vars ++ goal.post.vars)) ++ constants
        if goal.getType(ex) == v.getType(goal.gamma).get
        sigma = Map(ex -> v)
        newPost = goal.post.subst(sigma)
        newCallGoal = goal.callGoal.map(_.updateSubstitution(sigma))
        newGoal = goal.spawnChild(post = newPost, callGoal = newCallGoal)
        kont = ExistentialProducer(sigma) >> IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
      } yield RuleResult(List(newGoal), kont, this, goal)
    }
  }

  /**
    * Special pure synthesis rule for integer existentials that are only constrained by lower bounds
    * (useful for cardinalities).
    */
  object PickCard extends SynthesisRule with InvertibleRule {
    override def toString: String = "PickCard"

    def apply(goal: Goal): Seq[RuleResult] = {
      // If e is of the form lo < v, get lo
      def getLowerBound(e: Expr, v: Var): Option[Expr] = e match {
        case BinaryExpr(OpLt, lo, v1) if v1 == v => Some(lo)
        case _ => None
      }

      // Expression that computes the maximum of all es
      def maxExpr(es: Seq[Expr]): Expr =
        es.reduceLeft((e1, e2) => IfThenElse(BinaryExpr(OpLt, e1, e2), e2, e1))

      val lower_bounded = for {
        ex <- goal.existentials.toList
        if goal.getType(ex) == CardType
        boundOpts = goal.post.phi.conjuncts.filter(_.vars.contains(ex)).map(e => getLowerBound(e, ex))
        if boundOpts.forall(_.isDefined)
      } yield (ex, boundOpts.map(_.get).toList)

      for {
        (ex, bounds) <- lower_bounded.take(1)
        sol = if (bounds.isEmpty) IntConst(0) else BinaryExpr(OpPlus, maxExpr(bounds), IntConst(1))
        sigma = Map(ex -> sol)
        newPost = goal.post.subst(sigma)
        newCallGoal = goal.callGoal.map(_.updateSubstitution(sigma))
        newGoal = goal.spawnChild(post = newPost, callGoal = newCallGoal)
        kont = ExistentialProducer(sigma) >> IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
      } yield RuleResult(List(newGoal), kont, this, goal)
    }
  }

  /*
   If there's an argument in the call of the suspended recursive call goal that hasn't been substituted yet,
   try substituting it with the companion's goal argument at the same position.
   This rule is useful when there's an argument that does not appear in the precondition (see e.g. sll/sll-init).
   This strategy is incomplete, but it mimics our previous approach of not treating companion vars as existentials at all.
  */
  object PickArg extends SynthesisRule {
    override def toString: String = "PickArg"

    def apply(goal: Goal): Seq[RuleResult] = {
      if (goal.callGoal.isEmpty || goal.existentials.nonEmpty) return Nil // no suspended call or still has existentials
      val callGoal = goal.callGoal.get

      if (callGoal.call.companion.isEmpty) return Nil // suspended call is not recursive
      val companion = goal.ancestorWithLabel(callGoal.call.companion.get).get.toFunSpec

      def isUnsubstituted(e: Expr) = e.isInstanceOf[Var] && !callGoal.freshToActual.keySet.contains(e.asInstanceOf[Var])
      val i = callGoal.call.args.indexWhere(isUnsubstituted)
      if (i < 0) return Nil // no unsubstituted arguments remain

      val arg = companion.params(i)._1
      val sigma = Map(callGoal.call.args(i).asInstanceOf[Var] -> arg)
      val newPost = goal.post.subst(sigma)
      val newCallGoal = goal.callGoal.map(_.updateSubstitution(sigma))
      val newGoal = goal.spawnChild(post = newPost, callGoal = newCallGoal)
      val kont = ExistentialProducer(sigma) >> IdProducer >> HandleGuard(goal) >> ExtractHelper(goal)
      List(RuleResult(List(newGoal), kont, this, goal))
    }
  }


}

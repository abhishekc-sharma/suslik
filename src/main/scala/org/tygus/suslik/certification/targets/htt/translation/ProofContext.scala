package org.tygus.suslik.certification.targets.htt.translation

import org.tygus.suslik.certification.targets.htt.language.Expressions.{CExpr, CSApp, CSFormula, CSubst, CVar}
import org.tygus.suslik.certification.targets.htt.language.Types.HTTType
import org.tygus.suslik.certification.targets.htt.logic.{Hint, Proof}
import org.tygus.suslik.certification.targets.htt.logic.Sentences.{CAssertion, CInductivePredicate}
import org.tygus.suslik.certification.targets.htt.translation.ProofContext.{AppliedConstructor, PredicateEnv}
import org.tygus.suslik.certification.traversal.Evaluator.{ClientContext, EvaluatorException}

import scala.collection.immutable.ListMap
import scala.collection.mutable.ListBuffer

case class ProofContext(// Map of predicates referenced by the spec
                        predicates: PredicateEnv = Map.empty,
                        // Post-condition existentials
                        postEx: ListMap[CVar, (HTTType, CExpr)] = ListMap.empty,
                        // Used to look up the latest form of a predicate application, after variable substitutions
                        appAliases: Map[CSApp, CSApp] = Map.empty,
                        // Unfoldings that occur during evaluation, used to calculate heap existentials
                        unfoldings: Map[CSApp, AppliedConstructor] = Map.empty,
                        // A nested proof goal; present during call abduction
                        callGoal: Option[Proof.Goal] = None,
                        // Used to generate a unique name for a heap generated by a call
                        nextCallId: Int = 0,
                        // Hints accumulated during evaluation
                        hints: ListBuffer[Hint] = new ListBuffer[Hint],
                        // Current number of subgoals; used to calculate how many times to shelve/unshelve
                        numSubgoals: Int = 0,
                        // Used to track which post-condition predicate assertions remain to be solved
                        appsToSolve: Seq[CSApp] = Seq.empty)
  extends ClientContext[Proof.Step] {

  /**
    * Get existentials that result from unfolding an app; heap existentials are provided in
    * their maximally unfolded form (i.e., accounts for nested unfolds)
    */
  def existentialsAfterUnfolding(app: CSApp): (Seq[CExpr], Seq[CSFormula]) = {
    unfoldings.get(app) match {
      case Some(constructor) =>
        val heapEx = constructor.asn.sigma.apps.map(unfoldedApp)
        (constructor.existentials, heapEx)
      case _ => (Seq.empty, Seq.empty)
    }
  }

  /**
    * Get new apps that result from unfolding an app
    */
  def newAppsAfterUnfolding(app: CSApp): Seq[CSApp] = {
    unfoldings.get(app) match {
      case Some(constructor) => constructor.asn.sigma.apps
      case None => Nil
    }
  }

  /**
    * Solve specified app and update the list of apps to solve
    */
  def solveApp(app: CSApp, newApps: Seq[CSApp]): ProofContext = {
    appsToSolve.indexOf(app) match {
      case -1 => throw EvaluatorException(s"${app.pp} not found in list of predicate applications to solve")
      case idx =>
        val (before, after) = appsToSolve.splitAt(idx)
        this.copy(appsToSolve = before ++ newApps ++ after.tail)
    }
  }

  /**
    * Get the latest version of an app, which may change if one of its argument variables is substituted
    */
  def currentAppAlias(app: CSApp): CSApp = appAliases.get(app) match {
    case None => app
    case Some(app1) => if (app == app1) app else currentAppAlias(app1)
  }

  /**
    * Get the maximally unfolded heap equivalent of an app
    */
  def unfoldedApp(app: CSApp): CSFormula = unfoldings.get(app) match {
    case None => CSFormula(app.heapName, Seq(app), Seq.empty)
    case Some(constructor) =>
      val sigma = constructor.asn.sigma
      val (apps, ptss) = sigma.apps.map(unfoldedApp).map(h => (h.apps, h.ptss)).unzip
      CSFormula(app.heapName, apps.flatten, sigma.ptss ++ ptss.flatten)
  }

  /**
    * Update the current context with new substitutions
    */
  def withSubst(m: Map[CVar, CExpr], affectedApps: Map[CSApp, CSApp]): ProofContext = {
    val postEx1 = postEx.map { case (k, v) => k -> (v._1, v._2.subst(m)) }
    val appAliases1 = affectedApps.foldLeft(appAliases) { case (appAliases, (app, alias)) => appAliases + (app -> alias) + (alias -> alias) }
    val unfoldings1 = unfoldings.map { case (app, constructor) => app.subst(m) -> constructor.subst(m) }
    this.copy(postEx = postEx1, appAliases = appAliases1, unfoldings = unfoldings1)
  }

  def withUnify(preApp: CSApp, postApp: CSApp, m: Map[CExpr, CExpr]): ProofContext = {
    val m1 = m.toSeq.map(p => (p._2, p._1)).toMap
    val postEx1 = postEx.map { case (k, v) => k -> (v._1, v._2.substExpr(m1)) }
    val appAliases1 = appAliases + (postApp -> preApp)
    val unfoldings1 = unfoldings.map { case (app, c) =>
      val app1 = if (app == postApp) preApp else app
      val asn1 = c.asn.copy(sigma = c.asn.sigma.copy(apps = c.asn.sigma.apps.map(a => if (a == postApp) preApp else a)))
      val existentials1 = c.existentials.map(_.substExpr(m1))
      val c1 = c.copy(existentials = existentials1, asn = asn1)
      app1 -> c1
    }
    this.copy(postEx = postEx1, appAliases = appAliases1, unfoldings = unfoldings1)
  }

  def appsAffectedBySubst(m: Map[CVar, CExpr]): Map[CSApp, CSApp] =
    appAliases.foldLeft(Map[CSApp, CSApp]()) { case (affectedApps, (app, alias)) =>
      if (app == alias) {
        val app1 = app.subst(m)
        if (app != app1) {
          affectedApps + (app -> app1)
        } else affectedApps
      } else affectedApps
    }
}

object ProofContext {
  type PredicateEnv = Map[String, CInductivePredicate]
  case class AppliedConstructor(idx: Int, existentials: Seq[CExpr], asn: CAssertion) {
    def subst(sub: CSubst): AppliedConstructor =
      this.copy(existentials = existentials.map(_.subst(sub)), asn = asn.subst(sub))
  }
}

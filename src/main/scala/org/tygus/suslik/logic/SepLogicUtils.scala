package org.tygus.suslik.logic

import org.tygus.suslik.SSLException
import org.tygus.suslik.language.Expressions.{Expr, IntConst, Var}

/**
  * Utilities for spatial formulae
  *
  * @author Nadia Polikarpova, Ilya Sergey
  */

trait SepLogicUtils extends PureLogicUtils {

  protected def slAssert(assertion: Boolean, msg: String): Unit = if (!assertion) throw SepLogicException(msg)

  def emp: SFormula = SFormula(Nil)
  def singletonHeap(h:Heaplet): SFormula = SFormula(List(h))
  def mkSFormula(hs: List[Heaplet]) = SFormula(hs)

  /**
    * Get the heaplet satisfying the predicate
    */
  def findHeaplet(p: (Heaplet) => Boolean,
                  sigma: SFormula): Option[Heaplet] = sigma.chunks.find(p)

  /**
    * Get heaplets from pre and post satisfying a relation
    */
  def findMatchingHeaplets(pl: Heaplet => Boolean,
                           pr: (Heaplet, Heaplet) => Boolean,
                           pre: SFormula,
                           post: SFormula): Option[(Heaplet, Heaplet)] = {
    (for {hl <- pre.chunks.toStream if pl(hl)
          hr <- post.chunks.toStream if pr(hl, hr)} yield (hl, hr)).headOption
  }

  /**
    * Are two heaplets both points-to with the same LHS?
    */
  def sameLhs(hl: Heaplet): Heaplet => Boolean = hr => {
    if (!hl.isInstanceOf[PointsTo]) false
    else {
      val pt = hl.asInstanceOf[PointsTo]
      hr match {
        case PointsTo(y, off, _) => pt.loc == y && pt.offset == off
        case _ => false
      }
    }
  }

  /**
    * Find a block satisfying a predicates, and all matching chunks.
    * Returns None if not all chunks are present.
    */
  def findBlockAndChunks(pBlock: Heaplet => Boolean,
                         pPts: Heaplet => Boolean,
                         sigma: SFormula): Option[(Block, Seq[Heaplet])] = {
    findHeaplet(h => h.isInstanceOf[Block] && pBlock(h), sigma) match {
      case None => None
      case Some(h@Block(x@Var(_), sz)) =>
        val pts = for (off <- 0 until sz) yield
          findHeaplet(h => sameLhs(PointsTo(x, off, IntConst(0)))(h) && pPts(h), sigma)
        Some((h, pts.flatten))
      case Some(h) =>
        None // todo: is this correct?
      // For example find heaplet can stop at PointsTo, not reach the block and return None.
      // I think there should be findHeaplet(pBlock and isBlock, sigma) match { ...

    }
  }

  def respectsOrdering(goalSubHeap: SFormula, adaptedFunPre: SFormula): Boolean = {
//    def compareTags(lilTag: Option[Int], largTag: Option[Int]) = (lilTag, largTag) match {
//      case (Some(x), Some(y)) => x - y
//      case _ => 0
//    }

    def compareTags(lilTag: Option[Int], largTag: Option[Int]) = lilTag.getOrElse(0) - largTag.getOrElse(0)

    val pairTags = for {
      SApp(name, args, t) <- adaptedFunPre.chunks
      SApp(_name, _args, _t) <- goalSubHeap.chunks.find {
        case SApp(_name, _args, _) => _name == name && _args == args
        case _ => false
      }
    } yield (t, _t)
    val comparisons = pairTags.map {case (t, s) => compareTags(t, s)}
    val allGeq = comparisons.forall(_ <= 0)
    val atLeastOneLarger = comparisons.exists(_ < 0)
    allGeq && atLeastOneLarger
  }



  /**
    * Find the set of sub-formulas of `large` that `small` might possibly by unified with.
    */
  def findLargestMatchingHeap(small: SFormula, large: SFormula): Seq[SFormula] = {

    def findMatchingFor(h: Heaplet, stuff: Seq[Heaplet]): Seq[Heaplet] = h match {
      case Block(loc, sz) => stuff.filter {
        case Block(_, _sz) => sz == _sz
        case _ => false
      }
      case PointsTo(loc, offset, value) =>
        def hasBlockForLoc(_loc: Expr) = stuff.exists {
          case Block(_loc1, _) => _loc == _loc1
          case _ => false
        }

        stuff.filter {
          case PointsTo(_loc, _offset, _value) => offset == _offset // && !hasBlockForLoc(_loc)
          case _ => false
        }
      case SApp(pred, args, tag) => stuff.filter {
        case SApp(_pred, _args, _tag) =>
          _pred == pred && args.length == _args.length
        case _ => false
      }
    }

    def goFind(lil: List[Heaplet], larg: List[Heaplet], acc: List[List[Heaplet]]): List[List[Heaplet]] = lil match {
      case h :: hs => (for {
        h1 <- findMatchingFor(h, larg)
        larg1 = larg.filter(_ != h1)
        acc1 = acc.map(hs1 => h1 :: hs1)
        res <- goFind(hs, larg1, acc1)
      } yield res).toList
      case Nil => acc
    }

    goFind(small.chunks, large.chunks, List(Nil)).map(SFormula)
  }

  def findBlockRootedSubHeap(b: Block, sf: SFormula): Option[SFormula] = {
    if (!sf.chunks.contains(b)) return None
    val Block(x, sz) = b
    if (!x.isInstanceOf[Var]) return None
    val ps = for {p@PointsTo(y, o, _) <- sf.chunks if x == y} yield p
    val offsets = ps.map { case PointsTo(_, o, _) => o }.sorted
    val goodChunks = offsets.size == sz && // All offsets are present
      offsets.distinct.size == offsets.size && // No repetitions
      offsets.forall(o => o < sz) // all smaller than sz
    if (goodChunks) Some(mkSFormula(b :: ps)) else None
  }

  def chunksForUnifying(f: SFormula): List[Heaplet] = {
    val blocks = f.blocks
    val apps = f.apps
    val ptss = f.ptss
    // Now remove block-dependent chunks
    val chunksToRemove = (for {
      b <- blocks
      sf <- findBlockRootedSubHeap(b, f).toList
      c <- sf.chunks
    } yield c).toSet
    val res = blocks ++ apps ++ ptss.filterNot(p => chunksToRemove.contains(p))
    res
  }

}

case class SepLogicException(msg: String) extends SSLException("seplog", msg)


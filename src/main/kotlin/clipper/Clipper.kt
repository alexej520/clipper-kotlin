@file:JvmName("ClipperUtils")

package clipper

import clipper.propertyHolder.withRef
import clipper.propertyHolder.withRefGet
import clipper.propertyHolder.withRefs
import java.math.BigInteger
import kotlin.reflect.KMutableProperty0

//use_lines: Enables open path clipping. Adds a very minor cost to performance.
internal const val USE_LINES = true

//When kotlin.Int (32bit ints) are used instead of kotlin.Long (64bit ints). This
//may improve performance but coordinate values are limited to the range +/- 46340

internal typealias CInt = kotlin.Long // kotlin.Int
internal const val ZERO = 0L // 0
internal const val LO_RANGE: CInt = 0x3FFFFFFF // 0x7FFF
internal const val HI_RANGE: CInt = 0x3FFFFFFFFFFFFFFFL // 0x7FFF
internal fun bigMul(i1: CInt, i2: CInt) = BigInteger.valueOf(i1) * BigInteger.valueOf(i2) // i1.toLong() * i2
@Suppress("NOTHING_TO_INLINE")
inline fun round(value: Double): CInt =
        if (value < 0) (value - 0.5).toLong() else (value + 0.5).toLong()

typealias Path = MutableList<IntPoint>
@JvmName("newPath")
fun Path(): Path = mutableListOf()

@Suppress("NOTHING_TO_INLINE")
inline fun pathOf(vararg elements: IntPoint): Path = mutableListOf(*elements)

@JvmName("newPath")
fun Path(size: Int): Path = ArrayList(size)

inline fun Path(size: Int, init: (index: Int) -> IntPoint) = MutableList(size, init)

fun Path.calcArea(): Double {
    val size = size
    if (size < 3) return 0.0
    var a = 0.0
    var i = 0
    var j = size - 1
    while (i < size) {
        val polyI = get(i)
        val polyJ = get(j)
        a += (polyJ.x.toDouble() + polyI.x) * (polyJ.y.toDouble() - polyI.y)
        j = i
        ++i
    }
    return -a * 0.5
}

private fun Path.translate(delta: IntPoint): Path =
        MutableList(size) {
            with(get(it)) {
                IntPoint(x + delta.x, y + delta.y)
            }
        }


// SimplifyPolygon functions ...
// Convert self-intersecting polygons into simple polygons
@JvmName("simplifyPolygon")
fun Path.simplify(fillType: PolyFillType = PolyFillType.EvenOdd): Paths {
    val result = Paths()
    Clipper().apply {
        strictlySimple = true
        addPath(this@simplify, PolyType.Subject, true)
    }.execute(ClipType.Union, result, fillType, fillType)
    return result
}

val Path.orientation: Boolean
    get() {
        return calcArea() >= 0
    }

fun Path.containsPoint(pt: IntPoint): Int {
    //returns 0 if false, +1 if true, -1 if pt ON polygon boundary
    //See "The Point in Polygon Problem for Arbitrary Polygons" by Hormann & Agathos
    //http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.88.5498&rep=rep1&type=pdf
    var result = 0
    val size = size
    if (size < 3) return 0
    var ip = get(0)
    for (i in 1..size) {
        val ipNext = if (i == size) get(0) else get(i)
        if (ipNext.y == pt.y) {
            if (ipNext.x == pt.x || ip.y == pt.y && ipNext.x > pt.x == ip.x < pt.x)
                return -1
        }
        if (ip.y < pt.y != ipNext.y < pt.y) {
            if (ip.x >= pt.x) {
                if (ipNext.x > pt.x)
                    result = 1 - result
                else {
                    val d = (ip.x - pt.x).toDouble() * (ipNext.y - pt.y) - (ipNext.x - pt.x).toDouble() * (ip.y - pt.y)
                    if (d == 0.0)
                        return -1
                    else if (d > 0 == ipNext.y > ip.y) result = 1 - result
                }
            } else {
                if (ipNext.x > pt.x) {
                    val d = (ip.x - pt.x).toDouble() * (ipNext.y - pt.y) - (ipNext.x - pt.x).toDouble() * (ip.y - pt.y)
                    if (d == 0.0)
                        return -1
                    else if (d > 0 == ipNext.y > ip.y) result = 1 - result
                }
            }
        }
        ip = ipNext
    }
    return result
}

@JvmName("cleanPolygon")
fun Path.clean(distance: Double = 1.415): Path {
    //distance = proximity in units/pixels below which vertices will be stripped.
    //Default ~= sqrt(2) so when adjacent vertices or semi-adjacent vertices have
    //both x & y coords within 1 unit, then the second vertex will be stripped.

    var size = size

    if (size == 0) return pathOf()

    val outPts = Array(size) { OutPt(idx = 0, pt = get(it)) }

    outPts.forEachIndexed { i, outPt ->
        outPt.next = outPts[(i + 1) % size].apply { prev = outPt }
    }

    val distSqrd = distance * distance
    var op = outPts[0]
    while (op.idx == 0 && op.next != op.prev) {
        op = when {
            pointsAreClose(op.pt, op.prev.pt, distSqrd) -> {
                size--
                op.exclude()
            }
            pointsAreClose(op.prev.pt, op.next.pt, distSqrd) -> {
                size -= 2
                op.next.exclude()
                op.exclude()
            }
            slopesNearCollinear(op.prev.pt, op.pt, op.next.pt, distSqrd) -> {
                size--
                op.exclude()
            }
            else -> {
                op.idx = 1
                op.next
            }
        }
    }

    if (size < 3) size = 0
    return MutableList(size) { op.pt.also { op = op.next } }
}

@JvmName("cleanPolygons")
fun Paths.clean(distance: Double = 1.415): Paths =
        MutableList(size) { get(it).clean(distance) }

typealias Paths = MutableList<Path>
@JvmName("newPaths")
fun Paths(): Paths = mutableListOf()

@JvmName("newPaths")
fun Paths(size: Int): Paths = ArrayList(size)

@Suppress("NOTHING_TO_INLINE")
inline fun pathsOf(vararg elements: Path): Paths = mutableListOf(*elements)

inline fun Paths(size: Int, init: (index: Int) -> Path) = MutableList(size, init)

val Paths.bound: IntRect
    get() {
        var i = 0
        val size = size
        while (i < size && get(i).size == 0) i++
        if (i == size) return IntRect(0, 0, 0, 0)
        var left: CInt = 0 // lateinit
        var top: CInt = 0 // lateinit
        get(i)[0].let { left = it.x; top = it.y }
        val result = IntRect(left = left, right = left, top = top, bottom = top)
        (i until size).forEach {
            get(it).forEach { (x, y) ->
                if (x < result.left) {
                    result.left = x
                } else if (x > result.right) {
                    result.right = x
                }
                if (y < result.top) {
                    result.top = y
                } else if (y > result.bottom) {
                    result.bottom = y
                }
            }
        }
        return result
    }

fun Paths.reverseAll() {
    for (poly in this) {
        poly.reverse()
    }
}

@JvmName("simplifyPolygons")
fun Paths.simplify(fillType: PolyFillType = PolyFillType.EvenOdd): Paths {
    val result = Paths()
    Clipper().apply {
        strictlySimple = true
        addPaths(this@simplify, PolyType.Subject, true)
    }.execute(ClipType.Union, result, fillType, fillType)
    return result
}

typealias Ref<T> = KMutableProperty0<T>
typealias Out<T> = KMutableProperty0<T>

// C# keywords `ref` and `out` emulation
private object propertyHolder {
    class PropertyHolder {
        var value: Any? = null
        val property = this::value
    }

    val holders = mutableListOf<PropertyHolder>()
    var current = -1

    @Suppress("NOTHING_TO_INLINE")
    inline fun <T> getRef(): Ref<T> {
        current++
        if (holders.size == current) {
            holders += PropertyHolder()
        }
        @Suppress("UNCHECKED_CAST")
        return holders[current].property as Ref<T>
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun <T> clearRef(ref: Ref<T>) {
        @Suppress("UNCHECKED_CAST")
        (ref as Ref<Any?>).set(null)
        current--
    }

    inline fun <T> withRefGet(value: T, block: (Ref<T>) -> Unit): T {
        val ref = getRef<T>()
        ref.set(value)
        block(ref)
        val result = ref.get()
        clearRef(ref)
        return result
    }

    inline fun <T> withRefGet(block: (Ref<T>) -> Unit): T {
        val ref = getRef<T>()
        block(ref)
        val result = ref.get()
        clearRef(ref)
        return result
    }

    inline fun <T, R> withRef(block: (Ref<T>) -> R): R {
        val ref = getRef<T>()
        val result = block(ref)
        clearRef(ref)
        return result
    }

    inline fun <T, R> withRef(value: T, block: (Ref<T>) -> R): R {
        val ref = getRef<T>()
        ref.set(value)
        val result = block(ref)
        clearRef(ref)
        return result
    }

    inline fun <T1, T2, R> withRefs(block: (Ref<T1>, Ref<T2>) -> R): R {
        val ref1 = getRef<T1>()
        val ref2 = getRef<T2>()
        val result = block(ref1, ref2)
        clearRef(ref2)
        clearRef(ref1)
        return result
    }

    inline fun <T1, T2, R> withRefs(value1: T1, value2: T2, block: (Ref<T1>, Ref<T2>) -> R): R {
        val ref1 = getRef<T1>()
        val ref2 = getRef<T2>()
        ref1.set(value1)
        ref2.set(value2)
        val result = block(ref1, ref2)
        clearRef(ref2)
        clearRef(ref1)
        return result
    }

    inline fun <T1, T2, T3, R> withRefs(block: (Ref<T1>, Ref<T2>, Ref<T3>) -> R): R {
        val ref1 = getRef<T1>()
        val ref2 = getRef<T2>()
        val ref3 = getRef<T3>()
        val result = block(ref1, ref2, ref3)
        clearRef(ref3)
        clearRef(ref2)
        clearRef(ref1)
        return result
    }

    inline fun <T1, T2, T3, R> withRefs(value1: T1, value2: T2, value3: T3, block: (Ref<T1>, Ref<T2>, Ref<T3>) -> R): R {
        val ref1 = getRef<T1>()
        val ref2 = getRef<T2>()
        val ref3 = getRef<T3>()
        ref1.set(value1)
        ref2.set(value2)
        ref3.set(value3)
        val result = block(ref1, ref2, ref3)
        clearRef(ref3)
        clearRef(ref2)
        clearRef(ref1)
        return result
    }
}

data class DoublePoint(
        var x: Double = 0.0,
        var y: Double = 0.0
)

@Suppress("NOTHING_TO_INLINE")
inline infix fun DoublePoint.set(other: DoublePoint) {
    x = other.x
    y = other.y
}

class PolyTree : PolyNode() {
    internal val allPolys = mutableListOf<PolyNode>()

    //The GC probably handles this cleanup more efficiently ...
    //~PolyTree(){Clear();}

    fun clear() {
        allPolys.clear()
        (childs as MutableList<PolyNode>).clear()
    }

    val first: PolyNode? get() = childs.firstOrNull()

    val total: Int
        get() {
            var result = allPolys.size
            //with negative offsets, ignore the hidden outer polygon ...
            if (result > 0 && childs[0] != allPolys[0]) result--
            return result
        }
}

val PolyTree.openPaths: Paths
    get() = Paths(childCount).also { result ->
        childs.forEach { child ->
            if (child.isOpen)
                result += child.contour
        }
    }

val PolyTree.closedPaths: Paths
        get() = Paths(total).also { result -> result.add(this, NodeType.Closed) }

internal enum class NodeType { Any, Open, Closed }

val PolyTree.paths: Paths
        get() = Paths(total).also { result -> result.add(this, NodeType.Any) }

internal fun Paths.add(polynode: PolyNode, nt: NodeType) {
    val match = when (nt) {
        NodeType.Open -> return
        NodeType.Closed -> !polynode.isOpen
        else -> true
    }

    if (polynode.contour.isNotEmpty() && match)
        this += polynode.contour
    polynode.childs.forEach { pn ->
        add(pn, nt)
    }
}

open class PolyNode(
        internal val jointype: JoinType? = null,
        internal val endtype: EndType? = null
) {
    var parent: PolyNode? = null
    val contour: Path = pathOf()
    private var index = 0

    val childs: ArrayList<PolyNode> = ArrayList()

    val childCount: Int get() = childs.size

    internal fun addChild(child: PolyNode) {
        val cnt = childs.size
        childs += child
        child.parent = this
        child.index = cnt
    }

    fun getNext(): PolyNode? =
            if (childs.isNotEmpty()) {
                childs[0]
            } else {
                getNextSiblingUp()
            }

    internal fun getNextSiblingUp(): PolyNode? = parent.let { parent ->
        when {
            parent == null -> null
            index == parent.childs.size - 1 -> parent.getNextSiblingUp()
            else -> parent.childs[index + 1]
        }
    }

    val isHole: Boolean
        get() {
            var result = true
            var node = parent
            while (node != null) {
                result = !result
                node = node.parent
            }
            return result
        }

    var isOpen: Boolean = false
}

data class IntPoint(
        var x: CInt = 0,
        var y: CInt = 0
)

@Suppress("NOTHING_TO_INLINE")
inline infix fun IntPoint.set(other: IntPoint) {
    x = other.x
    y = other.y
}

@Suppress("NOTHING_TO_INLINE")
inline fun IntPoint.set(x: CInt, y: CInt) {
    this.x = x
    this.y = y
}

internal fun OutPt.hasVertex(ip: IntPoint): Boolean {
    var pp2 = this
    do {
        if (pp2.pt == ip) return true
        pp2 = pp2.next
    } while (pp2 != this)
    return false
}

internal fun IntPoint.isOnLineSegment(pt1: IntPoint, pt2: IntPoint, useFullRange: Boolean) =
        if (useFullRange) {
            this == pt1 || this == pt2
                    || ((x > pt1.x == x < pt2.x) && (y > pt1.y == y < pt2.y)
                    && bigMul(x - pt1.x, pt2.y - pt1.y)
                    == bigMul(pt2.x - pt1.x, y - pt1.y))

        } else {
            this == pt1 || this == pt2
                    || ((x > pt1.x == x < pt2.x) && (y > pt1.y == y < pt2.y)
                    && ((x - pt1.x) * (pt2.y - pt1.y)
                    == (pt2.x - pt1.x) * (y - pt1.y)))
        }

internal fun IntPoint.isOnPolygon(pp: OutPt, useFullRange: Boolean): Boolean {
    var pp2 = pp
    while (true) {
        if (isOnLineSegment(pp2.pt, pp2.next.pt, useFullRange)) {
            return true
        }
        pp2 = pp2.next
        if (pp2 == pp) {
            return false
        }
    }
}

class IntRect(
        var left: CInt,
        var top: CInt,
        var right: CInt,
        var bottom: CInt
) {
    constructor(other: IntRect) : this(other.left, other.top, other.right, other.bottom)
}

enum class ClipType { Intersection, Union, Difference, Xor }
enum class PolyType { Subject, Clip }

//By far the most widely used winding rules for polygon filling are
//EvenOdd & NonZero (GDI, GDI+, XLib, OpenGL, Cairo, AGG, Quartz, SVG, Gr32)
//Others rules include Positive, Negative and ABS_GTR_EQ_TWO (only in OpenGL)
//see http://glprogramming.com/red/chapter11.html

enum class PolyFillType { EvenOdd, NonZero, Positive, Negative }

enum class JoinType { Square, Round, Miter }
enum class EndType { ClosedPolygon, ClosedLine, OpenButt, OpenSquare, OpenRound }

internal enum class EdgeSide { Left, Right }
internal enum class Direction { RightToLeft, LeftToRight }

internal class TEdge {
    internal val bot: IntPoint = IntPoint()
    internal val curr: IntPoint = IntPoint() //current (updated for every new scanbeam)
    internal val top: IntPoint = IntPoint()
    internal val delta: IntPoint = IntPoint()
    internal var dx: Double = 0.0
    internal var polyTyp: PolyType? = null
    internal var side: EdgeSide? = null //side only refers to current side of solution poly
    internal var windDelta: Int = 0 //1 or -1 depending on winding direction
    internal var windCnt: Int = 0
    internal var windCnt2: Int = 0 //winding count of the opposite polytype
    internal var outIdx: Int = 0
    internal lateinit var next: TEdge
    internal lateinit var prev: TEdge
    internal var nextInLML: TEdge? = null
    internal var nextInAEL: TEdge? = null
    internal var prevInAEL: TEdge? = null
    internal var nextInSEL: TEdge? = null
    internal var prevInSEL: TEdge? = null
}

internal val TEdge.isHorizontal: Boolean get() = delta.y == ZERO

internal fun TEdge.setDx() {
    delta.x = top.x - bot.x
    delta.y = top.y - bot.y
    dx = if (delta.y == ZERO) HORIZONTAL else delta.x.toDouble() / delta.y
}

private fun TEdge.init(polyType: PolyType) {
    if (curr.y >= next.curr.y) {
        bot set curr
        top set next.curr
    } else {
        top set curr
        bot set next.curr
    }
    setDx()
    polyTyp = polyType
}

private fun TEdge.init(eNext: TEdge, ePrev: TEdge, pt: IntPoint) {
    next = eNext
    prev = ePrev
    curr set pt
    outIdx = UNASSIGNED
}

private fun TEdge.reverseHorizontal() {
    val tmp = top.x
    top.x = bot.x
    bot.x = tmp
}

internal class IntersectNode(
        internal val edge1: TEdge,
        internal val edge2: TEdge,
        internal val pt: IntPoint
)

private val IntersectNode.hasAdjacentEdges: Boolean
    get() = edge1.nextInSEL == edge2 || edge1.prevInSEL == edge2

internal object myIntersectNodeComparator : Comparator<IntersectNode> {
    override fun compare(node1: IntersectNode, node2: IntersectNode): Int {
        val i = node2.pt.y - node1.pt.y
        return if (i > 0) 1 else if (i < 0) -1 else 0
    }
}

internal class LocalMinima {
    internal var y: CInt = 0
    internal var leftBound: TEdge? = null
    internal var rightBound: TEdge? = null
    internal var next: LocalMinima? = null
}

internal class Scanbeam(
        internal var y: CInt = 0,
        internal var next: Scanbeam? = null
)

internal class Maxima {
    internal var x: CInt = 0
    internal var next: Maxima? = null
    internal var prev: Maxima? = null
}

//OutRec: containsPoint a path in the clipping solution. Edges in the AEL will
//carry a pointer to an OutRec when they are part of the clipping solution.
internal class OutRec {
    internal var idx: Int = UNASSIGNED
    internal var isHole: Boolean = false
    internal var isOpen: Boolean = false
    internal var firstLeft: OutRec? = null //see comments in clipper.pas
    internal var pts: OutPt? = null
    internal var bottomPt: OutPt? = null
    internal var polyNode: PolyNode? = null
}

internal fun OutRec.isRightOf(outRec2: OutRec): Boolean {
    var outRec1: OutRec? = this
    do {
        outRec1 = outRec1!!.firstLeft
        if (outRec1 == outRec2) return true
    } while (outRec1 != null)
    return false
}

private fun OutRec.updateOutPtIdxs() {
    var op = pts!!
    do {
        op.idx = idx
        op = op.prev
    } while (op != pts)
}

internal fun OutRec.calcArea(): Double =
        pts?.calcArea() ?: 0.0

internal class OutPt(
        internal var idx: Int,
        pt: IntPoint
) {
    internal val pt: IntPoint = IntPoint()

    init {
        this.pt set pt
    }

    internal lateinit var next: OutPt
    internal lateinit var prev: OutPt
}

private fun OutPt.reversePolyPtLinks() {
    var pp1: OutPt
    var pp2: OutPt
    pp1 = this
    do {
        pp2 = pp1.next
        pp1.next = pp1.prev
        pp1.prev = pp2
        pp1 = pp2
    } while (pp1 != this)
}

internal fun OutPt.calcArea(): Double {
    var op: OutPt = this
    var a = 0.0
    do {
        a += (op.prev.pt.x + op.pt.x).toDouble() * (op.prev.pt.y - op.pt.y).toDouble()
        op = op.next
    } while (op != this)
    return a * 0.5
}

//See "The Point in Polygon Problem for Arbitrary Polygons" by Hormann & Agathos
//http://citeseerx.ist.psu.edu/viewdoc/download?doi=10.1.1.88.5498&rep=rep1&type=pdf
private fun OutPt.containsPoint(pt: IntPoint): Int {
    var op = this
    //returns 0 if false, +1 if true, -1 if pt ON polygon boundary
    var result = 0
    val startOp = op
    val ptx = pt.x
    val pty = pt.y
    var poly0x = op.pt.x
    var poly0y = op.pt.y
    do {
        op = op.next
        val poly1x = op.pt.x
        val poly1y = op.pt.y

        if (poly1y == pty) {
            if (poly1x == ptx || poly0y == pty && poly1x > ptx == poly0x < ptx)
                return -1
        }
        if (poly0y < pty != poly1y < pty) {
            if (poly0x >= ptx) {
                if (poly1x > ptx)
                    result = 1 - result
                else {
                    val d = (poly0x - ptx).toDouble() * (poly1y - pty) - (poly1x - ptx).toDouble() * (poly0y - pty)
                    if (d == 0.0) return -1
                    if (d > 0 == poly1y > poly0y) result = 1 - result
                }
            } else {
                if (poly1x > ptx) {
                    val d = (poly0x - ptx).toDouble() * (poly1y - pty) - (poly1x - ptx).toDouble() * (poly0y - pty)
                    if (d == 0.0) return -1
                    if (d > 0 == poly1y > poly0y) result = 1 - result
                }
            }
        }
        poly0x = poly1x
        poly0y = poly1y
    } while (startOp != op)
    return result
}

private fun OutPt.containsPoint(polygon: OutPt): Boolean {
    var op = polygon
    do {
        //nb: PointInPolygon returns 0 if false, +1 if true, -1 if pt on polygon
        val res = containsPoint(op.pt)
        if (res >= 0) return res > 0
        op = op.next
    } while (op != polygon)
    return true
}

private fun OutPt.exclude(): OutPt {
    val result = prev
    result.next = next
    next.prev = result
    result.idx = 0
    return result
}

internal class Join(
        internal var outPt1: OutPt? = null,
        internal var outPt2: OutPt? = null,
        offPt: IntPoint
) {
    internal val offPt: IntPoint = IntPoint()

    init {
        this.offPt set offPt
    }
}

internal const val HORIZONTAL = -3.4E+38
internal const val SKIP = -2
internal const val UNASSIGNED = -1
internal const val TOLERANCE = 1.0E-20

@Suppress("NOTHING_TO_INLINE")
inline internal val Double.isNearZero: Boolean
    get() = (this > -TOLERANCE) && (this < TOLERANCE)

internal fun slopesEqual(e1: TEdge, e2: TEdge, useFullRange: Boolean): Boolean =
        if (useFullRange) {
            bigMul(e1.delta.y, e2.delta.x) == bigMul(e1.delta.x, e2.delta.y)
        } else {
            e1.delta.y * e2.delta.x == e1.delta.x * e2.delta.y
        }

internal fun slopesEqual(pt1: IntPoint, pt2: IntPoint, pt3: IntPoint, useFullRange: Boolean): Boolean =
        if (useFullRange) {
            bigMul(pt1.y - pt2.y, pt2.x - pt3.x) == bigMul(pt1.x - pt2.x, pt2.y - pt3.y)
        } else {
            (pt1.y - pt2.y) * (pt2.x - pt3.x) == (pt1.x - pt2.x) * (pt2.y - pt3.y)
        }

internal fun slopesEqual(pt1: IntPoint, pt2: IntPoint, pt3: IntPoint, pt4: IntPoint, useFullRange: Boolean): Boolean =
        if (useFullRange) {
            bigMul(pt1.y - pt2.y, pt3.x - pt4.x) == bigMul(pt1.x - pt2.x, pt3.y - pt4.y)
        } else {
            (pt1.y - pt2.y) * (pt3.x - pt4.x) == (pt1.x - pt2.x) * (pt3.y - pt4.y)
        }

internal fun pt2IsBetweenPt1AndPt3(pt1: IntPoint, pt2: IntPoint, pt3: IntPoint): Boolean {
    if ((pt1 == pt3) || (pt1 == pt2) || (pt3 == pt2)) return false
    else if (pt1.x != pt3.x) return (pt2.x > pt1.x) == (pt2.x < pt3.x)
    else return (pt2.y > pt1.y) == (pt2.y < pt3.y)
}

open class ClipperBase {
    internal var minimaList: LocalMinima? = null
    internal var currentLM: LocalMinima? = null
    internal var edges: MutableList<MutableList<TEdge>> = mutableListOf()
    internal var scanbeam: Scanbeam? = null
    internal var polyOuts: MutableList<OutRec> = mutableListOf()
    internal var activeEdges: TEdge? = null
    internal var useFullRange: Boolean = false
    internal var hasOpenPaths: Boolean = false

    var preserveCollinear: Boolean = false

    // ???
    open fun clear() {
        disposeLocalMinimaList()
        edges.forEach { edgeList ->
            edgeList.clear()
        }
    }

    // ???
    private fun disposeLocalMinimaList() {
        while (minimaList != null) {
            val tmpLm = minimaList!!.next
            minimaList = null
            minimaList = tmpLm
        }
        currentLM = null
    }

    fun rangeTest(pt: IntPoint, useFullRangeRef: KMutableProperty0<Boolean>) {
        if (useFullRangeRef.get()) {
            if (pt.x > HI_RANGE || pt.y > HI_RANGE || -pt.x > HI_RANGE || -pt.y > HI_RANGE)
                throw ClipperException("Coordinate outside allowed range")
        } else if (pt.x > LO_RANGE || pt.y > LO_RANGE || -pt.x > LO_RANGE || -pt.y > LO_RANGE) {
            useFullRangeRef.set(true)
            rangeTest(pt, useFullRangeRef)
        }
    }

    private fun findNextLocMin(e: TEdge): TEdge {
        var e = e
        var e2: TEdge
        while (true) {
            while (e.bot != e.prev.bot || e.curr == e.top) e = e.next
            if (e.dx != HORIZONTAL && e.prev.dx != HORIZONTAL) break
            while (e.prev.dx == HORIZONTAL) e = e.prev
            e2 = e
            while (e.dx == HORIZONTAL) e = e.next
            if (e.top.y == e.prev.bot.y) continue //ie just an intermediate horz.
            if (e2.prev.bot.x < e.bot.x) e = e2
            break
        }
        return e
    }

    private fun processBound(e: TEdge, leftBoundIsForward: Boolean): TEdge {
        var e = e
        var eStart: TEdge
        var result = e
        var horz: TEdge

        if (result.outIdx == SKIP) {
            //check if there are edges beyond the skip edge in the bound and if so
            //create another LocMin and calling ProcessBound once more ...
            e = result
            if (leftBoundIsForward) {
                while (e.top.y == e.next.bot.y) e = e.next
                while (e != result && e.dx == HORIZONTAL) e = e.prev
            } else {
                while (e.top.y == e.next.bot.y) e = e.prev
                while (e != result && e.dx == HORIZONTAL) e = e.next
            }
            if (e == result) {
                result = if (leftBoundIsForward) e.next else e.prev
            } else {
                //there are more edges in the bound beyond result starting with e
                e = if (leftBoundIsForward) result.next else result.prev
                val locMin = LocalMinima()
                locMin.next = null
                locMin.y = e.bot.y
                locMin.leftBound = null
                locMin.rightBound = e
                e.windDelta = 0
                result = processBound(e, leftBoundIsForward)
                insertLocalMinima(locMin)
            }
            return result
        }

        if (e.dx == HORIZONTAL) {
            //We need to be careful with open paths because this may not be a
            //true local minima (ie e may be following a skip edge).
            //Also, consecutive horz. edges may start heading left before going right.
            if (leftBoundIsForward)
                eStart = e.prev
            else
                eStart = e.next
            if (eStart.dx == HORIZONTAL)//ie an adjoining horizontal skip edge
            {
                if (eStart.bot.x != e.bot.x && eStart.top.x != e.bot.x) {
                    e.reverseHorizontal()
                }
            } else if (eStart.bot.x != e.bot.x) {
                e.reverseHorizontal()
            }
        }

        eStart = e
        if (leftBoundIsForward) {
            while (result.top.y == result.next.bot.y && result.next.outIdx != SKIP)
                result = result.next
            if (result.dx == HORIZONTAL && result.next.outIdx != SKIP) {
                //nb: at the top of a bound, horizontals are added to the bound
                //only when the preceding edge attaches to the horizontal's left vertex
                //unless a Skip edge is encountered when that becomes the top divide
                horz = result
                while (horz.prev.dx == HORIZONTAL) horz = horz.prev
                if (horz.prev.top.x > result.next.top.x) result = horz.prev
            }
            while (e != result) {
                e.nextInLML = e.next
                if (e.dx == HORIZONTAL && e != eStart && e.bot.x != e.prev.top.x)
                    e.reverseHorizontal()
                e = e.next
            }
            if (e.dx == HORIZONTAL && e != eStart && e.bot.x != e.prev.top.x)
                e.reverseHorizontal()
            result = result.next //move to the edge just beyond current bound
        } else {
            while (result.top.y == result.prev.bot.y && result.prev.outIdx != SKIP) result = result.prev

            if (result.dx == HORIZONTAL && result.prev.outIdx != SKIP) {
                horz = result
                while (horz.next.dx == HORIZONTAL) horz = horz.next
                if (horz.next.top.x == result.prev.top.x || horz.next.top.x > result.prev.top.x) {
                    result = horz.next
                }
            }

            while (e != result) {
                e.nextInLML = e.prev
                if (e.dx == HORIZONTAL && e != eStart && e.bot.x != e.next.top.x)
                    e.reverseHorizontal()
                e = e.prev
            }
            if (e.dx == HORIZONTAL && e != eStart && e.bot.x != e.next.top.x)
                e.reverseHorizontal()
            result = result.prev //move to the edge just beyond current bound
        }
        return result
    }

    fun addPath(pg: Path, polyType: PolyType, closed: Boolean): Boolean {
        if (USE_LINES) {
            if (!closed && polyType == PolyType.Clip)
                throw ClipperException("AddPath: Open paths must be subject.")
        } else {
            (!closed)
            throw ClipperException("AddPath: Open paths have been disabled.")
        }

        var highI = pg.size - 1
        if (closed) while (highI > 0 && (pg[highI] == pg[0])) --highI
        while (highI > 0 && (pg[highI] == pg[highI - 1])) --highI
        if ((closed && highI < 2) || (!closed && highI < 1)) return false

        //create a new edge array ...
        val edges: MutableList<TEdge> = ArrayList(highI + 1)
        (0..highI).forEach {
            edges += TEdge()
        }

        var isFlat = true

        //1. Basic (first) edge initialization ...
        edges[1].curr set pg[1]
        rangeTest(pg[0], this::useFullRange)
        rangeTest(pg[highI], this::useFullRange)
        edges[0].init(edges[1], edges[highI], pg[0])
        edges[highI].init(edges[0], edges[highI - 1], pg[highI])
        (highI - 1 downTo 1).forEach { i ->
            rangeTest(pg[i], this::useFullRange)
            edges[i].init(edges[i + 1], edges[i - 1], pg[i])
        }
        var eStart = edges[0]

        //2. Remove duplicate vertices, and (when closed) collinear edges ...
        var e = eStart
        var eLoopStop = eStart
        while (true) {
            //nb: allows matching start and end points when not Closed ...
            if (e.curr == e.next.curr && (closed || e.next != eStart)) {
                if (e == e.next) break
                if (e == eStart) eStart = e.next
                e = removeEdge(e)
                eLoopStop = e
                continue
            }
            if (e.prev == e.next)
                break //only two vertices
            else if (closed &&
                    slopesEqual(e.prev.curr, e.curr, e.next.curr, useFullRange) &&
                    (!preserveCollinear || !pt2IsBetweenPt1AndPt3(e.prev.curr, e.curr, e.next.curr))) {
                //Collinear edges are allowed for open paths but in closed paths
                //the default is to merge adjacent collinear edges into a single edge.
                //However, if the PreserveCollinear property is enabled, only overlapping
                //collinear edges (ie spikes) will be removed from closed paths.
                if (e == eStart) eStart = e.next
                e = removeEdge(e)
                e = e.prev
                eLoopStop = e
                continue
            }
            e = e.next
            if ((e == eLoopStop) || (!closed && e.next == eStart)) break
        }

        if ((!closed && (e == e.next)) || (closed && (e.prev == e.next)))
            return false

        if (!closed) {
            hasOpenPaths = true
            eStart.prev.outIdx = SKIP
        }

        //3. Do second stage of edge initialization ...
        e = eStart
        do {
            e.init(polyType)
            e = e.next
            if (isFlat && e.curr.y != eStart.curr.y) isFlat = false
        } while (e != eStart)

        //4. Finally, add edge bound to LocalMinima list ...

        //Totally flat paths must be handled differently when adding them
        //to LocalMinima list to avoid endless loops etc ...
        if (isFlat) {
            if (closed) return false
            e.prev.outIdx = SKIP
            val locMin = LocalMinima().apply {
                next = null
                y = e.bot.y
                leftBound = null
                rightBound = e.apply {
                    side = EdgeSide.Right
                    windDelta = 0
                }
            }
            while (true) {
                if (e.bot.x != e.prev.top.x) e.reverseHorizontal()
                if (e.next.outIdx == SKIP) break
                e.nextInLML = e.next
                e = e.next
            }
            insertLocalMinima(locMin)
            this.edges.add(edges)
            return true
        }

        this.edges.add(edges)
        var leftBoundIsForward: Boolean
        var eMin: TEdge? = null

        //workaround to avoid an endless loop in the while loop below when
        //open paths have matching start and end points ...
        if (e.prev.bot == e.prev.top) e = e.next

        while (true) {
            e = findNextLocMin(e)
            if (e == eMin) break
            else if (eMin == null) eMin = e

            //E and E.Prev now share a local minima (left aligned if horizontal).
            //Compare their slopes to find which starts which bound ...
            val locMin = LocalMinima().apply {
                next = null
                y = e.bot.y
                if (e.dx < e.prev.dx) {
                    leftBound = e.prev
                    rightBound = e
                    leftBoundIsForward = false
                } else {
                    leftBound = e
                    rightBound = e.prev
                    leftBoundIsForward = true
                }
                leftBound!!.side = EdgeSide.Left
                rightBound!!.side = EdgeSide.Right
                when {
                    !closed -> leftBound!!.windDelta = 0
                    leftBound!!.next == rightBound -> leftBound!!.windDelta = -1
                    else -> leftBound!!.windDelta = 1
                }
                rightBound!!.windDelta = -leftBound!!.windDelta

                e = processBound(leftBound!!, leftBoundIsForward)
                if (e.outIdx == SKIP) e = processBound(e, leftBoundIsForward)
                var e2 = processBound(rightBound!!, !leftBoundIsForward)
                if (e2.outIdx == SKIP) e2 = processBound(e2, !leftBoundIsForward)
                when (SKIP) {
                    leftBound!!.outIdx -> leftBound = null
                    rightBound!!.outIdx -> rightBound = null
                }
                if (!leftBoundIsForward) e = e2
            }
            insertLocalMinima(locMin)
        }
        return true
    }

    fun addPaths(ppg: Paths, polyType: PolyType, closed: Boolean): Boolean {
        var result = false
        ppg.forEach { pg ->
            if (addPath(pg, polyType, closed)) {
                result = true
            }
        }
        return result
    }

    internal fun removeEdge(e: TEdge): TEdge = with(e) {
        //removes e from double_linked_list (but without removing from memory)
        prev.next = next
        next.prev = prev
        val result = next
        //prev = null //flag as removed (see ClipperBase.Clear) ???
        return result
    }

    private fun insertLocalMinima(newLm: LocalMinima): Unit =
            when {
                minimaList == null -> minimaList = newLm
                newLm.y >= minimaList!!.y -> {
                    newLm.next = minimaList
                    minimaList = newLm
                }
                else -> {
                    var tmpLm = minimaList
                    while (tmpLm!!.next != null && newLm.y < tmpLm.next!!.y)
                        tmpLm = tmpLm.next
                    newLm.next = tmpLm.next
                    tmpLm.next = newLm
                }
            }

    internal fun popLocalMinima(y: CInt, currentOut: KMutableProperty0<LocalMinima?>): Boolean {
        currentOut.set(currentLM)
        if (currentLM != null && currentLM!!.y == y) {
            currentLM = currentLM!!.next
            return true
        }
        return false
    }

    open internal fun reset() {
        currentLM = minimaList
        if (currentLM == null) return //ie nothing to process

        //reset all edges ...
        scanbeam = null
        var lm = minimaList
        while (lm != null) {
            insertScanbeam(lm.y)
            lm.leftBound?.apply {
                curr set bot
                outIdx = UNASSIGNED
            }
            lm.rightBound?.apply {
                curr set bot
                outIdx = UNASSIGNED
            }
            lm = lm.next
        }
        activeEdges = null
    }

    internal fun insertScanbeam(y: CInt): Unit =
            when {
                scanbeam == null -> scanbeam = Scanbeam(y = y, next = null)
                y > scanbeam!!.y -> scanbeam = Scanbeam(y = y, next = scanbeam)
                else -> {
                    var sb2 = scanbeam!!
                    while (sb2.next != null && (y <= sb2.next!!.y)) sb2 = sb2.next!!
                    if (y == sb2.y) { //ie ignores duplicates
                        Unit
                    } else {
                        sb2.next = Scanbeam(y = y, next = sb2.next)
                    }
                }
            }

    internal fun popScanbeam(yOut: KMutableProperty0<CInt>): Boolean {
        if (scanbeam == null) {
            yOut.set(0)
            return false
        }
        yOut.set(scanbeam!!.y)
        scanbeam = scanbeam!!.next
        return true
    }

    internal fun localMinimaPending(): Boolean = currentLM != null

    internal fun createOutRec(): OutRec = OutRec().apply {
        polyOuts.add(this)
        idx = polyOuts.size - 1
    }

    internal fun updateEdgeIntoAEL(eRef: KMutableProperty0<TEdge>) {
        eRef.get().apply {
            val nextInLML = nextInLML ?: throw ClipperException("UpdateEdgeIntoAEL: invalid call")
            val aelPrev = prevInAEL
            val aelNext = nextInAEL
            nextInLML.outIdx = outIdx
            if (aelPrev != null) {
                aelPrev.nextInAEL = nextInLML
            } else {
                activeEdges = nextInLML
            }
            if (aelNext != null) {
                aelNext.prevInAEL = nextInLML
            }
            nextInLML.side = side
            nextInLML.windDelta = windDelta
            nextInLML.windCnt = windCnt
            nextInLML.windCnt2 = windCnt2

            eRef.set(nextInLML.apply {
                curr set bot
                prevInAEL = aelPrev
                nextInAEL = aelNext
                if (!isHorizontal) insertScanbeam(top.y)
            })
        }
    }

    internal fun swapPositionsInAEL(edge1: TEdge, edge2: TEdge) {
        //check that one or other edge hasn't already been removed from AEL ...
        if (edge1.nextInAEL == edge1.prevInAEL || edge2.nextInAEL == edge2.prevInAEL) return

        if (edge1.nextInAEL == edge2) {
            val next = edge2.nextInAEL?.apply { prevInAEL = edge1 }
            val prev = edge1.prevInAEL?.apply { nextInAEL = edge2 }
            edge2.prevInAEL = prev
            edge2.nextInAEL = edge1
            edge1.prevInAEL = edge2
            edge1.nextInAEL = next
        } else if (edge2.nextInAEL == edge1) {
            val next = edge1.nextInAEL?.apply { prevInAEL = edge2 }
            val prev = edge2.prevInAEL?.apply { nextInAEL = edge1 }
            edge1.prevInAEL = prev
            edge1.nextInAEL = edge2
            edge2.prevInAEL = edge1
            edge2.nextInAEL = next
        } else {
            val next = edge1.nextInAEL
            val prev = edge1.prevInAEL
            edge1.nextInAEL = edge2.nextInAEL?.apply { prevInAEL = edge1 }
            edge1.prevInAEL = edge2.prevInAEL?.apply { nextInAEL = edge1 }
            edge2.nextInAEL = next?.apply { prevInAEL = edge2 }
            edge2.prevInAEL = prev?.apply { nextInAEL = edge2 }
        }

        if (edge1.prevInAEL == null)
            activeEdges = edge1
        else if (edge2.prevInAEL == null)
            activeEdges = edge2
    }

    internal fun deleteFromAEL(e: TEdge) {
        val aelPrev = e.prevInAEL
        val aelNext = e.nextInAEL
        if (aelPrev == null && aelNext == null && (e != activeEdges))
            return //already deleted
        if (aelPrev != null) {
            aelPrev.nextInAEL = aelNext
        } else {
            activeEdges = aelNext
        }
        aelNext?.prevInAEL = aelPrev
        e.nextInAEL = null
        e.prevInAEL = null
    }
} //end ClipperBase

private fun getDx(pt1: IntPoint, pt2: IntPoint): Double {
    if (pt1.y == pt2.y)
        return HORIZONTAL
    else
        return (pt2.x - pt1.x).toDouble() / (pt2.y - pt1.y)
}

private fun swapSides(edge1: TEdge, edge2: TEdge) {
    val side = edge1.side
    edge1.side = edge2.side
    edge2.side = side
}

private fun swapPolyIndexes(edge1: TEdge, edge2: TEdge) {
    val outIdx = edge1.outIdx
    edge1.outIdx = edge2.outIdx
    edge2.outIdx = outIdx
}

private fun intersectNodeSort(node1: IntersectNode, node2: IntersectNode): Int =
        //the following typecast is safe because the differences in Pt.Y will
        //be limited to the height of the scanbeam.
        (node2.pt.y - node1.pt.y).toInt()

private fun topX(edge: TEdge, currentY: CInt): CInt {
    if (currentY == edge.top.y)
        return edge.top.x
    return edge.bot.x + round(edge.dx * (currentY - edge.bot.y))
}

private fun parseFirstLeft(firstLeft: OutRec?): OutRec? {
    var firstLeft = firstLeft
    while (firstLeft != null && firstLeft.pts == null)
        firstLeft = firstLeft.firstLeft
    return firstLeft // ???
}

private fun distanceSqrd(pt1: IntPoint, pt2: IntPoint): Double {
    val dx = pt1.x.toDouble() - pt2.x
    val dy = pt1.y.toDouble() - pt2.y
    return dx * dx + dy * dy
}

private fun distanceFromLineSqrd(pt: IntPoint, ln1: IntPoint, ln2: IntPoint): Double {
    //The equation of a line in general form (Ax + By + C = 0)
    //given 2 points (x¹,y¹) & (x²,y²) is ...
    //(y¹ - y²)x + (x² - x¹)y + (y² - y¹)x¹ - (x² - x¹)y¹ = 0
    //A = (y¹ - y²); B = (x² - x¹); C = (y² - y¹)x¹ - (x² - x¹)y¹
    //perpendicular distance of point (x³,y³) = (Ax³ + By³ + C)/Sqrt(A² + B²)
    //see http://en.wikipedia.org/wiki/Perpendicular_distance
    val A = (ln1.y - ln2.y).toDouble()
    val B = (ln2.x - ln1.x).toDouble()
    var C = A * ln1.x + B * ln1.y
    C = A * pt.x + B * pt.y - C
    return C * C / (A * A + B * B)
}

private fun slopesNearCollinear(
        pt1: IntPoint, pt2: IntPoint, pt3: IntPoint, distSqrd: Double): Boolean {
    //this function is more accurate when the point that's GEOMETRICALLY
    //between the other 2 points is the one that's tested for distance.
    //nb: with 'spikes', either pt1 or pt3 is geometrically between the other pts
    return (if (Math.abs(pt1.x - pt2.x) > Math.abs(pt1.y - pt2.y)) {
        if (pt1.x > pt2.x == pt1.x < pt3.x)
            distanceFromLineSqrd(pt1, pt2, pt3)
        else if (pt2.x > pt1.x == pt2.x < pt3.x)
            distanceFromLineSqrd(pt2, pt1, pt3)
        else
            distanceFromLineSqrd(pt3, pt1, pt2)
    } else {
        if (pt1.y > pt2.y == pt1.y < pt3.y)
            distanceFromLineSqrd(pt1, pt2, pt3)
        else if (pt2.y > pt1.y == pt2.y < pt3.y)
            distanceFromLineSqrd(pt2, pt1, pt3)
        else
            distanceFromLineSqrd(pt3, pt1, pt2)
    } < distSqrd)
}

private fun pointsAreClose(pt1: IntPoint, pt2: IntPoint, distSqrd: Double): Boolean {
    val dx = pt1.x.toDouble() - pt2.x
    val dy = pt1.y.toDouble() - pt2.y
    return dx * dx + dy * dy <= distSqrd
}

internal fun minkowski(pattern: Path, path: Path, isSum: Boolean, isClosed: Boolean): Paths {
    val delta = if (isClosed) 1 else 0
    val polyCnt = pattern.size
    val pathCnt = path.size
    val result = Paths(pathCnt)
    if (isSum)
        path.forEach { pt ->
            val p = Path(polyCnt)
            pattern.forEach { ip ->
                p += IntPoint(pt.x + ip.x, pt.y + ip.y)
            }
            result += p
        }
    else
        path.forEach { pt ->
            val p = Path(polyCnt)
            pattern.forEach { ip ->
                p += IntPoint(pt.x - ip.x, pt.y - ip.y)
            }
            result += p
        }

    val quads = Paths((pathCnt + delta) * (polyCnt + 1))

    (0 until pathCnt - 1 + delta).forEach { i ->
        (0 until polyCnt).forEach { j ->
            val quad = pathOf(
                    result[i % pathCnt][j % polyCnt],
                    result[(i + 1) % pathCnt][j % polyCnt],
                    result[(i + 1) % pathCnt][(j + 1) % polyCnt],
                    result[i % pathCnt][(j + 1) % polyCnt]
            )
            if (!quad.orientation) quad.reverse()
            quads += quad
        }
    }
    return quads
}

@JvmName("minkowskiSum2")
fun minkowskiSum(pattern: Path, path: Path, pathIsClosed: Boolean): Paths =
        minkowski(pattern, path, true, pathIsClosed).also { paths ->
            Clipper().apply {
                addPaths(paths, PolyType.Subject, true)
            }.execute(ClipType.Union, paths, PolyFillType.NonZero, PolyFillType.NonZero)
        }

fun minkowskiSum(pattern: Path, paths: Paths, pathIsClosed: Boolean): Paths =
        Paths().also { solution ->
            Clipper().apply {
                paths.forEach { pathI ->
                    val tmp = minkowski(pattern, pathI, true, pathIsClosed)
                    addPaths(tmp, PolyType.Subject, true)
                    if (pathIsClosed) {
                        val path = pathI.translate(pattern[0])
                        addPath(path, PolyType.Clip, true)
                    }
                }
            }.execute(ClipType.Union, solution, PolyFillType.NonZero, PolyFillType.NonZero)
        }

fun minkowskiDiff(poly1: Path, poly2: Path): Paths =
        minkowski(poly1, poly2, false, true).also { paths ->
            Clipper().apply {
                addPaths(paths, PolyType.Subject, true)
            }.execute(ClipType.Union, paths, PolyFillType.NonZero, PolyFillType.NonZero)
        }

class Clipper(
        initOptions: Int = 0
) : ClipperBase() {

    companion object {
        //InitOptions that can be passed to the constructor ...
        const val REVERSE_SOLUTION = 1
        const val STRICTLY_SIMPLE = 2
        const val PRESERVE_COLLINEAR = 4
    }

    private var clipType: ClipType? = null
    private var maxima: Maxima? = null
    private var sortedEdges: TEdge? = null
    private val intersectList: MutableList<IntersectNode> = mutableListOf()
    internal var intersectNodeComparator: Comparator<IntersectNode> = myIntersectNodeComparator
    private var executeLocked: Boolean = false
    private var clipFillType: PolyFillType? = null
    private var subjFillType: PolyFillType? = null
    private val joins: MutableList<Join> = mutableListOf()
    private val ghostJoins: MutableList<Join> = mutableListOf()
    private var usingPolyTree: Boolean = false
    var reverseSolution: Boolean = (REVERSE_SOLUTION and initOptions) != 0
    var strictlySimple: Boolean = (STRICTLY_SIMPLE and initOptions) != 0

    init {
        preserveCollinear = (PRESERVE_COLLINEAR and initOptions) != 0
    }

    private fun insertMaxima(x: CInt) {
        //double-linked list: sorted ascending, ignoring dups.
        val newMax = Maxima()
        newMax.x = x
        val maxima = maxima
        if (maxima == null) {
            this.maxima = newMax
        } else if (x < maxima.x) {
            newMax.next = maxima
            this.maxima = newMax
        } else {
            var m = maxima
            while (m!!.next != null && x >= m.next!!.x) m = m.next
            if (x == m.x) return  //ie ignores duplicates (& CG to clean up newMax)
            //insert newMax between m and m.Next ...
            newMax.next = m.next
            newMax.prev = m
            m.next?.prev = newMax
            m.next = newMax
        }
    }

    fun execute(clipType: ClipType, solution: Paths, fillType: PolyFillType = PolyFillType.EvenOdd): Boolean {
        return execute(clipType, solution, fillType, fillType)
    }

    fun execute(clipType: ClipType, polyTree: PolyTree, fillType: PolyFillType = PolyFillType.EvenOdd): Boolean {
        return execute(clipType, polyTree, fillType, fillType)
    }

    fun execute(clipType: ClipType, solution: Paths, subjFillType: PolyFillType, clipFillType: PolyFillType): Boolean {
        if (executeLocked) return false
        if (hasOpenPaths)
            throw ClipperException("Error: PolyTree struct is needed for open path clipping.")

        executeLocked = true
        solution.clear()
        this.subjFillType = subjFillType
        this.clipFillType = clipFillType
        this.clipType = clipType
        usingPolyTree = false
        val succeeded: Boolean
        try {
            succeeded = executeInternal()
            //build the return polygons ...
            if (succeeded) buildResult(solution)
        } finally {
            disposeAllPolyPts()
            executeLocked = false
        }
        return succeeded
    }

    fun execute(clipType: ClipType, polytree: PolyTree,
                subjFillType: PolyFillType, clipFillType: PolyFillType): Boolean {
        if (executeLocked) return false
        executeLocked = true
        this.subjFillType = subjFillType
        this.clipFillType = clipFillType
        this.clipType = clipType
        this.usingPolyTree = true
        val succeeded: Boolean
        try {
            succeeded = executeInternal()
            //build the return polygons ...
            if (succeeded) buildResult2(polytree)
        } finally {
            disposeAllPolyPts()
            executeLocked = false
        }
        return succeeded
    }

    internal fun fixHoleLinkage(outRec: OutRec) {
        //skip if an outermost polygon or
        //already already points to the correct FirstLeft ...
        val firstLeft = outRec.firstLeft
        if (firstLeft == null
                || (outRec.isHole != firstLeft.isHole && firstLeft.pts != null)) {
            return
        }
        var orfl = firstLeft
        while (orfl != null && ((orfl.isHole == outRec.isHole) || orfl.pts == null))
            orfl = orfl.firstLeft
        outRec.firstLeft = orfl
    }

    private fun executeInternal(): Boolean {
        try {
            reset()
            sortedEdges = null
            maxima = null
            var botY = ZERO
            var topY = ZERO
            botY = withRefGet(botY) { ref -> if (!popScanbeam(ref)) return false }
            insertLocalMinimaIntoAEL(botY)
            while (withRef(topY) { ref ->
                popScanbeam(ref).also {
                    topY = ref.get()
                }
            } || localMinimaPending()) {
                processHorizontals()
                ghostJoins.clear()
                if (!processIntersections(topY)) return false
                processEdgesAtTopOfScanbeam(topY)
                botY = topY
                insertLocalMinimaIntoAEL(botY)
            }
            //fix orientations ...
            for (outRec in polyOuts) {
                if (outRec.pts == null || outRec.isOpen) continue
                if ((outRec.isHole xor reverseSolution) == outRec.calcArea() > 0)
                    outRec.pts?.reversePolyPtLinks()
            }
            joinCommonEdges()
            for (outRec in polyOuts) {
                if (outRec.pts == null)
                    continue
                else if (outRec.isOpen)
                    fixupOutPolyline(outRec)
                else
                    fixupOutPolygon(outRec)
            }

            if (strictlySimple) doSimplePolygons()
            return true
        }
        //catch { return false; }
        finally {
            joins.clear()
            ghostJoins.clear()
        }
    }

    private fun disposeAllPolyPts() {
        polyOuts.clear()
    }

    private fun addJoin(op1: OutPt?, op2: OutPt?, offPt: IntPoint) {
        joins += Join(outPt1 = op1, outPt2 = op2, offPt = offPt)
    }

    private fun addGhostJoin(op: OutPt, offPt: IntPoint) {
        ghostJoins += Join(outPt1 = op, offPt = offPt)
    }

    private fun insertLocalMinimaIntoAEL(botY: CInt) {
        var lm: LocalMinima? = null
        while (withRef<LocalMinima?, Boolean> { ref ->
            popLocalMinima(botY, ref).also {
                lm = ref.get()
            }
        }) {
            val lb = lm!!.leftBound
            val rb = lm!!.rightBound

            var op1: OutPt? = null
            when {
                lb == null -> {
                    insertEdgeIntoAEL(rb!!, null)
                    setWindingCount(rb)
                    if (isContributing(rb))
                        op1 = addOutPt(rb, rb.bot)
                }
                rb == null -> {
                    insertEdgeIntoAEL(lb, null)
                    setWindingCount(lb)
                    if (isContributing(lb))
                        op1 = addOutPt(lb, lb.bot)
                    insertScanbeam(lb.top.y)
                }
                else -> {
                    insertEdgeIntoAEL(lb, null)
                    insertEdgeIntoAEL(rb, lb)
                    setWindingCount(lb)
                    rb.windCnt = lb.windCnt
                    rb.windCnt2 = lb.windCnt2
                    if (isContributing(lb))
                        op1 = addLocalMinPoly(lb, rb, lb.bot)
                    insertScanbeam(lb.top.y)
                }
            }

            if (rb != null) {
                if (rb.isHorizontal) {
                    rb.nextInLML?.let { insertScanbeam(it.top.y) }
                    addEdgeToSEL(rb)
                } else
                    insertScanbeam(rb.top.y)
            }

            if (lb == null || rb == null) continue

            //if output polygons share an Edge with a horizontal rb, they'll need joining later ...
            if (op1 != null && rb.isHorizontal && ghostJoins.isNotEmpty() && rb.windDelta != 0) {
                ghostJoins.forEach { j ->
                    if (horzSegmentsOverlap(j.outPt1!!.pt.x, j.offPt.x, rb.bot.x, rb.top.x)) {
                        addJoin(j.outPt1, op1, j.offPt)
                    }
                }
            }

            val lbPrevInAEL = lb.prevInAEL

            if (lb.outIdx >= 0 && lbPrevInAEL != null &&
                    lbPrevInAEL.curr.x == lb.bot.x &&
                    lbPrevInAEL.outIdx >= 0 &&
                    slopesEqual(lbPrevInAEL.curr, lbPrevInAEL.top, lb.curr, lb.top, useFullRange) &&
                    lb.windDelta != 0 && lbPrevInAEL.windDelta != 0) {
                val op2 = addOutPt(lbPrevInAEL, lb.bot)
                addJoin(op1, op2, lb.top)
            }

            if (lb.nextInAEL != rb) {
                val rbPrevInAEL = rb.prevInAEL!!
                if (rb.outIdx >= 0 && rbPrevInAEL.outIdx >= 0 &&
                        slopesEqual(rbPrevInAEL.curr, rbPrevInAEL.top, rb.curr, rb.top, useFullRange) &&
                        rb.windDelta != 0 && rbPrevInAEL.windDelta != 0) {
                    val op2 = addOutPt(rbPrevInAEL, rb.bot)
                    addJoin(op1, op2, rb.top)
                }

                var e = lb.nextInAEL
                if (e != null) {
                    while (e != rb) {
                        //nb: For calculating winding counts etc, IntersectEdges() assumes
                        //that param1 will be to the right of param2 ABOVE the intersection ...
                        intersectEdges(rb, e!!, lb.curr) //order important here
                        e = e.nextInAEL
                    }
                }
            }
        }
    }

    private fun insertEdgeIntoAEL(edge: TEdge, startEdge: TEdge?) {
        var startEdge = startEdge
        val activeEdges = activeEdges
        if (activeEdges == null) {
            edge.prevInAEL = null
            edge.nextInAEL = null
            this.activeEdges = edge
        } else if (startEdge == null && e2InsertsBeforeE1(activeEdges, edge)) {
            edge.prevInAEL = null
            edge.nextInAEL = activeEdges
            activeEdges.prevInAEL = edge
            this.activeEdges = edge
        } else {
            if (startEdge == null) startEdge = activeEdges
            while (startEdge!!.nextInAEL?.let { !e2InsertsBeforeE1(it, edge) } ?: false) {
                startEdge = startEdge.nextInAEL
            }
            edge.nextInAEL = startEdge.nextInAEL
            startEdge.nextInAEL?.prevInAEL = edge
            edge.prevInAEL = startEdge
            startEdge.nextInAEL = edge
        }
    }

    private fun e2InsertsBeforeE1(e1: TEdge, e2: TEdge): Boolean {
        val result = if (e2.curr.x == e1.curr.x) {
            if (e2.top.y > e1.top.y)
                e2.top.x < topX(e1, e2.top.y)
            else
                e1.top.x > topX(e2, e1.top.y)
        } else
            e2.curr.x < e1.curr.x
        return result
    }

    private fun isEvenOddFillType(edge: TEdge): Boolean =
            if (edge.polyTyp == PolyType.Subject)
                subjFillType == PolyFillType.EvenOdd
            else
                clipFillType == PolyFillType.EvenOdd

    private fun isEvenOddAltFillType(edge: TEdge): Boolean =
            if (edge.polyTyp == PolyType.Subject)
                clipFillType == PolyFillType.EvenOdd
            else
                subjFillType == PolyFillType.EvenOdd


    private fun isContributing(edge: TEdge): Boolean {
        val pft: PolyFillType
        val pft2: PolyFillType
        if (edge.polyTyp == PolyType.Subject) {
            pft = subjFillType!!
            pft2 = clipFillType!!
        } else {
            pft = clipFillType!!
            pft2 = subjFillType!!
        }

        when (pft) {
            PolyFillType.EvenOdd ->
                //return false if a subj line has been flagged as inside a subj polygon
                if (edge.windDelta == 0 && edge.windCnt != 1) return false
            PolyFillType.NonZero -> if (Math.abs(edge.windCnt) != 1) return false
            PolyFillType.Positive -> if (edge.windCnt != 1) return false
            else //PolyFillType.pftNegative
            -> if (edge.windCnt != -1) return false
        }

        return when (clipType) {
            ClipType.Intersection -> {
                when (pft2) {
                    PolyFillType.EvenOdd, PolyFillType.NonZero -> edge.windCnt2 != 0
                    PolyFillType.Positive -> edge.windCnt2 > 0
                    else -> edge.windCnt2 < 0
                }
            }
            ClipType.Union -> {
                when (pft2) {
                    PolyFillType.EvenOdd, PolyFillType.NonZero -> edge.windCnt2 == 0
                    PolyFillType.Positive -> edge.windCnt2 <= 0
                    else -> edge.windCnt2 >= 0
                }
            }
            ClipType.Difference -> {
                if (edge.polyTyp == PolyType.Subject)
                    when (pft2) {
                        PolyFillType.EvenOdd, PolyFillType.NonZero -> edge.windCnt2 == 0
                        PolyFillType.Positive -> edge.windCnt2 <= 0
                        else -> edge.windCnt2 >= 0
                    }
                else
                    when (pft2) {
                        PolyFillType.EvenOdd, PolyFillType.NonZero -> edge.windCnt2 != 0
                        PolyFillType.Positive -> edge.windCnt2 > 0
                        else -> edge.windCnt2 < 0
                    }
            }
            ClipType.Xor -> {
                if (edge.windDelta == 0)
                    when (pft2) {
                        PolyFillType.EvenOdd, PolyFillType.NonZero -> edge.windCnt2 == 0
                        PolyFillType.Positive -> edge.windCnt2 <= 0
                        else -> edge.windCnt2 >= 0
                    }
                else
                    true
            }
            else -> true
        }
    }

    private fun setWindingCount(edge: TEdge) {
        var e = edge.prevInAEL
        //find the edge of the same polytype that immediately preceeds 'edge' in AEL
        while (e != null && (e.polyTyp != edge.polyTyp || e.windDelta == 0)) e = e.prevInAEL
        e = when {
            e == null -> {
                val pft = if (edge.polyTyp == PolyType.Subject) subjFillType else clipFillType
                edge.windCnt = if (edge.windDelta == 0)
                    if (pft == PolyFillType.Negative) -1 else 1
                else
                    edge.windDelta
                edge.windCnt2 = 0
                activeEdges //ie get ready to calc WindCnt2
            }
            edge.windDelta == 0 && clipType != ClipType.Union -> {
                edge.windCnt = 1
                edge.windCnt2 = e.windCnt2
                e.nextInAEL //ie get ready to calc WindCnt2
            }
            isEvenOddFillType(edge) -> {
                //EvenOdd filling ...
                edge.windCnt = if (edge.windDelta == 0) {
                    //are we inside a subj polygon ...
                    var inside = true
                    var e2 = e.prevInAEL
                    while (e2 != null) {
                        if (e2.polyTyp == e.polyTyp && e2.windDelta != 0)
                            inside = !inside
                        e2 = e2.prevInAEL
                    }
                    if (inside) 0 else 1
                } else {
                    edge.windDelta
                }
                edge.windCnt2 = e.windCnt2
                e.nextInAEL //ie get ready to calc WindCnt2
            }
            else -> {
                //nonZero, Positive or Negative filling ...
                edge.windCnt = if (e.windCnt * e.windDelta < 0) {
                    //prev edge is 'decreasing' WindCount (WC) toward zero
                    //so we're outside the previous polygon ...
                    if (Math.abs(e.windCnt) > 1) {
                        //outside prev poly but still inside another.
                        //when reversing direction of prev poly use the same WC
                        if (e.windDelta * edge.windDelta < 0)
                            e.windCnt
                        else
                            e.windCnt + edge.windDelta//otherwise continue to 'decrease' WC ...
                    } else {
                        //now outside all polys of same polytype so set own WC ...
                        if (edge.windDelta == 0) 1 else edge.windDelta
                    }
                } else {
                    //prev edge is 'increasing' WindCount (WC) away from zero
                    //so we're inside the previous polygon ...
                    when {
                        edge.windDelta == 0 -> if (e.windCnt < 0) e.windCnt - 1 else e.windCnt + 1
                        e.windDelta * edge.windDelta < 0 -> e.windCnt
                        else -> e.windCnt + edge.windDelta
                    }//otherwise add to WC ...
                    //if wind direction is reversing prev then use same WC
                }
                edge.windCnt2 = e.windCnt2
                e.nextInAEL //ie get ready to calc WindCnt2
            }
        }

        //update WindCnt2 ...
        if (isEvenOddAltFillType(edge)) {
            //EvenOdd filling ...
            while (e != edge) {
                if (e!!.windDelta != 0)
                    edge.windCnt2 = if (edge.windCnt2 == 0) 1 else 0
                e = e.nextInAEL
            }
        } else {
            //nonZero, Positive or Negative filling ...
            while (e != edge) {
                edge.windCnt2 += e!!.windDelta
                e = e.nextInAEL
            }
        }
    }

    private fun addEdgeToSEL(edge: TEdge) {
        //SEL pointers in PEdge are use to build transient lists of horizontal edges.
        //However, since we don't need to worry about processing order, all additions
        //are made to the front of the list ...
        val sortedEdges = sortedEdges
        if (sortedEdges == null) {
            edge.prevInSEL = null
            edge.nextInSEL = null
        } else {
            edge.nextInSEL = sortedEdges
            edge.prevInSEL = null
            sortedEdges.prevInSEL = edge
        }
        this.sortedEdges = edge
    }

    internal fun popEdgeFromSEL(eOut: KMutableProperty0<TEdge?>): Boolean {
        //Pop edge from front of SEL (ie SEL is a FILO list)
        val e = sortedEdges
        eOut.set(e)
        if (e == null) return false
        val oldE = e
        sortedEdges = e.nextInSEL
        sortedEdges?.prevInSEL = null
        oldE.nextInSEL = null
        oldE.prevInSEL = null
        return true
    }

    private fun copyAELToSEL() {
        var e = activeEdges
        sortedEdges = e
        while (e != null) {
            e.prevInSEL = e.prevInAEL
            e.nextInSEL = e.nextInAEL
            e = e.nextInAEL
        }
    }

    private fun swapPositionsInSEL(edge1: TEdge, edge2: TEdge) {
        if (edge1.nextInSEL == null && edge1.prevInSEL == null)
            return
        if (edge2.nextInSEL == null && edge2.prevInSEL == null)
            return

        if (edge1.nextInSEL == edge2) {
            val next = edge2.nextInSEL
            next?.prevInSEL = edge1
            val prev = edge1.prevInSEL
            prev?.nextInSEL = edge2
            edge2.prevInSEL = prev
            edge2.nextInSEL = edge1
            edge1.prevInSEL = edge2
            edge1.nextInSEL = next
        } else if (edge2.nextInSEL == edge1) {
            val next = edge1.nextInSEL
            next?.prevInSEL = edge2
            val prev = edge2.prevInSEL
            prev?.nextInSEL = edge1
            edge1.prevInSEL = prev
            edge1.nextInSEL = edge2
            edge2.prevInSEL = edge1
            edge2.nextInSEL = next
        } else {
            val next = edge1.nextInSEL
            val prev = edge1.prevInSEL
            edge1.nextInSEL = edge2.nextInSEL
            edge1.nextInSEL?.prevInSEL = edge1
            edge1.prevInSEL = edge2.prevInSEL
            edge1.prevInSEL?.nextInSEL = edge1
            edge2.nextInSEL = next
            edge2.nextInSEL?.prevInSEL = edge2
            edge2.prevInSEL = prev
            edge2.prevInSEL?.nextInSEL = edge2
        }

        if (edge1.prevInSEL == null)
            sortedEdges = edge1
        else if (edge2.prevInSEL == null)
            sortedEdges = edge2
    }


    private fun addLocalMaxPoly(e1: TEdge, e2: TEdge, pt: IntPoint) {
        addOutPt(e1, pt)
        if (e2.windDelta == 0) addOutPt(e2, pt)
        if (e1.outIdx == e2.outIdx) {
            e1.outIdx = UNASSIGNED
            e2.outIdx = UNASSIGNED
        } else if (e1.outIdx < e2.outIdx)
            appendPolygon(e1, e2)
        else
            appendPolygon(e2, e1)
    }

    private fun addLocalMinPoly(e1: TEdge, e2: TEdge, pt: IntPoint): OutPt {
        val result: OutPt
        val e: TEdge
        val prevE: TEdge?
        if (e2.isHorizontal || e1.dx > e2.dx) {
            result = addOutPt(e1, pt)
            e2.outIdx = e1.outIdx
            e1.side = EdgeSide.Left
            e2.side = EdgeSide.Right
            e = e1
            prevE = if (e.prevInAEL == e2) e2.prevInAEL else e.prevInAEL
        } else {
            result = addOutPt(e2, pt)
            e1.outIdx = e2.outIdx
            e1.side = EdgeSide.Right
            e2.side = EdgeSide.Left
            e = e2
            prevE = if (e.prevInAEL == e1) e1.prevInAEL else e.prevInAEL
        }

        if (prevE != null && prevE.outIdx >= 0 && prevE.top.y < pt.y && e.top.y < pt.y) {
            val xPrev: CInt = topX(prevE, pt.y)
            val xE: CInt = topX(e, pt.y)
            if (xPrev == xE && e.windDelta != 0 && prevE.windDelta != 0 &&
                    slopesEqual(IntPoint(xPrev, pt.y), prevE.top, IntPoint(xE, pt.y), e.top, useFullRange)) {
                val outPt = addOutPt(prevE, pt)
                addJoin(result, outPt, e.top)
            }
        }
        return result
    }

    private fun addOutPt(e: TEdge, pt: IntPoint): OutPt {
        if (e.outIdx < 0) {
            val outRec = createOutRec()
            outRec.isOpen = e.windDelta == 0
            val newOp = OutPt(idx = outRec.idx, pt = pt).apply {
                next = this
                prev = this
            }
            outRec.pts = newOp
            if (!outRec.isOpen)
                setHoleState(e, outRec)
            e.outIdx = outRec.idx //nb: do this after SetZ !
            return newOp
        } else {
            val outRec = polyOuts[e.outIdx]
            //OutRec.Pts is the 'Left-most' point & OutRec.Pts.Prev is the 'Right-most'
            val op = outRec.pts!!
            val toFront = e.side == EdgeSide.Left
            if (toFront && pt == op.pt)
                return op
            else if (!toFront && pt == op.prev.pt) return op.prev

            val newOp = OutPt(idx = outRec.idx, pt = pt)
            newOp.next = op
            newOp.prev = op.prev
            newOp.prev.next = newOp
            op.prev = newOp
            if (toFront) outRec.pts = newOp
            return newOp
        }
    }

    private fun getLastOutPt(e: TEdge): OutPt =
            if (e.side == EdgeSide.Left)
                polyOuts[e.outIdx].pts!!
            else
                polyOuts[e.outIdx].pts!!.prev

    private fun horzSegmentsOverlap(seg1a: CInt, seg1b: CInt, seg2a: CInt, seg2b: CInt): Boolean {
        var seg1a = seg1a
        var seg1b = seg1b
        var seg2a = seg2a
        var seg2b = seg2b

        if (seg1a > seg1b) {
            val tmp = seg1a
            seg1a = seg1b
            seg1b = tmp
        }
        if (seg2a > seg2b) {
            val tmp = seg2a
            seg2a = seg2b
            seg2b = tmp
        }
        return (seg1a < seg2b) && (seg2a < seg1b)
    }

    private fun setHoleState(e: TEdge, outRec: OutRec) {
        var e2 = e.prevInAEL
        var eTmp: TEdge? = null
        while (e2 != null) {
            if (e2.outIdx >= 0 && e2.windDelta != 0) {
                if (eTmp == null)
                    eTmp = e2
                else if (eTmp.outIdx == e2.outIdx)
                    eTmp = null //paired
            }
            e2 = e2.prevInAEL
        }

        if (eTmp == null) {
            outRec.firstLeft = null
            outRec.isHole = false
        } else {
            polyOuts[eTmp.outIdx].let { firstLeft ->
                outRec.firstLeft = firstLeft
                outRec.isHole = !firstLeft.isHole
            }
        }
    }

    private fun firstIsBottomPt(btmPt1: OutPt, btmPt2: OutPt): Boolean {
        var p = btmPt1.prev
        while (p.pt == btmPt1.pt && p != btmPt1) p = p.prev
        val dx1p = Math.abs(getDx(btmPt1.pt, p.pt))
        p = btmPt1.next
        while (p.pt == btmPt1.pt && p != btmPt1) p = p.next
        val dx1n = Math.abs(getDx(btmPt1.pt, p.pt))

        p = btmPt2.prev
        while (p.pt == btmPt2.pt && p != btmPt2) p = p.prev
        val dx2p = Math.abs(getDx(btmPt2.pt, p.pt))
        p = btmPt2.next
        while (p.pt == btmPt2.pt && p != btmPt2) p = p.next
        val dx2n = Math.abs(getDx(btmPt2.pt, p.pt))

        if (Math.max(dx1p, dx1n) == Math.max(dx2p, dx2n) && Math.min(dx1p, dx1n) == Math.min(dx2p, dx2n))
            return btmPt1.calcArea() > 0 //if otherwise identical use orientation
        else
            return dx1p >= dx2p && dx1p >= dx2n || dx1n >= dx2p && dx1n >= dx2n
    }

    private fun getBottomPt(pp: OutPt): OutPt {
        var pp = pp
        var dups: OutPt? = null
        var p = pp.next
        while (p != pp) {
            if (p.pt.y > pp.pt.y) {
                pp = p
                dups = null
            } else if (p.pt.y == pp.pt.y && p.pt.x <= pp.pt.x) {
                if (p.pt.x < pp.pt.x) {
                    dups = null
                    pp = p
                } else {
                    if (p.next != pp && p.prev != pp) dups = p
                }
            }
            p = p.next
        }
        if (dups != null) {
            //there appears to be at least 2 vertices at bottomPt so ...
            while (dups!! != p) {
                if (!firstIsBottomPt(p, dups)) pp = dups
                dups = dups.next
                while (dups!!.pt != pp.pt) dups = dups.next
            }
        }
        return pp
    }

    private fun getLowermostRec(outRec1: OutRec, outRec2: OutRec): OutRec {
        //work out which polygon fragment has the correct hole state ...
        if (outRec1.bottomPt == null)
            outRec1.bottomPt = getBottomPt(outRec1.pts!!)
        if (outRec2.bottomPt == null)
            outRec2.bottomPt = getBottomPt(outRec2.pts!!)
        val bPt1 = outRec1.bottomPt!!
        val bPt2 = outRec2.bottomPt!!
        return when {
            bPt1.pt.y > bPt2.pt.y -> outRec1
            bPt1.pt.y < bPt2.pt.y -> outRec2
            bPt1.pt.x < bPt2.pt.x -> outRec1
            bPt1.pt.x > bPt2.pt.x -> outRec2
            bPt1.next == bPt1 -> outRec2
            bPt2.next == bPt2 -> outRec1
            firstIsBottomPt(bPt1, bPt2) -> outRec1
            else -> outRec2
        }
    }

    private fun getOutRec(idx: Int): OutRec {
        var outrec = polyOuts[idx]
        while (outrec != polyOuts[outrec.idx])
            outrec = polyOuts[outrec.idx]
        return outrec
    }

    private fun appendPolygon(e1: TEdge, e2: TEdge) {
        val outRec1 = polyOuts[e1.outIdx]
        val outRec2 = polyOuts[e2.outIdx]

        val holeStateRec: OutRec
        if (outRec1.isRightOf(outRec2))
            holeStateRec = outRec2
        else if (outRec2.isRightOf(outRec1))
            holeStateRec = outRec1
        else
            holeStateRec = getLowermostRec(outRec1, outRec2)

        //get the start and ends of both output polygons and
        //join E2 poly onto E1 poly and delete pointers to E2 ...
        val p1_lft = outRec1.pts!!
        val p1_rt = p1_lft.prev
        val p2_lft = outRec2.pts!!
        val p2_rt = p2_lft.prev

        //join e2 poly onto e1 poly and delete pointers to e2 ...
        if (e1.side == EdgeSide.Left) {
            if (e2.side == EdgeSide.Left) {
                //z y x a b c
                p2_lft.reversePolyPtLinks()
                p2_lft.next = p1_lft
                p1_lft.prev = p2_lft
                p1_rt.next = p2_rt
                p2_rt.prev = p1_rt
                outRec1.pts = p2_rt
            } else {
                //x y z a b c
                p2_rt.next = p1_lft
                p1_lft.prev = p2_rt
                p2_lft.prev = p1_rt
                p1_rt.next = p2_lft
                outRec1.pts = p2_lft
            }
        } else {
            if (e2.side == EdgeSide.Right) {
                //a b c z y x
                p2_lft.reversePolyPtLinks()
                p1_rt.next = p2_rt
                p2_rt.prev = p1_rt
                p2_lft.next = p1_lft
                p1_lft.prev = p2_lft
            } else {
                //a b c x y z
                p1_rt.next = p2_lft
                p2_lft.prev = p1_rt
                p1_lft.prev = p2_rt
                p2_rt.next = p1_lft
            }
        }

        outRec1.bottomPt = null
        if (holeStateRec == outRec2) {
            outRec2.firstLeft.let { firstLeft ->
                if (firstLeft != outRec1)
                    outRec1.firstLeft = firstLeft
            }
            outRec1.isHole = outRec2.isHole
        }
        outRec2.pts = null
        outRec2.bottomPt = null

        outRec2.firstLeft = outRec1

        val oKIdx = e1.outIdx
        val obsoleteIdx = e2.outIdx

        e1.outIdx = UNASSIGNED //nb: safe because we only get here via AddLocalMaxPoly
        e2.outIdx = UNASSIGNED

        var e = activeEdges
        while (e != null) {
            if (e.outIdx == obsoleteIdx) {
                e.outIdx = oKIdx
                e.side = e1.side
                break
            }
            e = e.nextInAEL
        }
        outRec2.idx = outRec1.idx
    }

    private fun intersectEdges(e1: TEdge, e2: TEdge, pt: IntPoint) {
        //e1 will be to the left of e2 BELOW the intersection. Therefore e1 is before
        //e2 in AEL except when e1 is being inserted at the intersection point ...

        val e1Contributing = (e1.outIdx >= 0)
        val e2Contributing = (e2.outIdx >= 0)

        if (USE_LINES) {
            //if either edge is on an OPEN path ...
            if (e1.windDelta == 0 || e2.windDelta == 0) {
                //ignore subject-subject open path intersections UNLESS they
                //are both open paths, AND they are both 'contributing maximas' ...
                if (e1.windDelta == 0 && e2.windDelta == 0) return
                //if intersecting a subj line with a subj poly ...
                else if (e1.polyTyp == e2.polyTyp &&
                        e1.windDelta != e2.windDelta && clipType == ClipType.Union) {
                    if (e1.windDelta == 0) {
                        if (e2Contributing) {
                            addOutPt(e1, pt)
                            if (e1Contributing) e1.outIdx = UNASSIGNED
                        }
                    } else {
                        if (e1Contributing) {
                            addOutPt(e2, pt)
                            if (e2Contributing) e2.outIdx = UNASSIGNED
                        }
                    }
                } else if (e1.polyTyp != e2.polyTyp) {
                    if ((e1.windDelta == 0) && Math.abs(e2.windCnt) == 1 &&
                            (clipType != ClipType.Union || e2.windCnt2 == 0)) {
                        addOutPt(e1, pt)
                        if (e1Contributing) e1.outIdx = UNASSIGNED
                    } else if ((e2.windDelta == 0) && (Math.abs(e1.windCnt) == 1) &&
                            (clipType != ClipType.Union || e1.windCnt2 == 0)) {
                        addOutPt(e2, pt)
                        if (e2Contributing) e2.outIdx = UNASSIGNED
                    }
                }
                return
            }
        }

        //update winding counts...
        //assumes that e1 will be to the Right of e2 ABOVE the intersection
        if (e1.polyTyp == e2.polyTyp) {
            if (isEvenOddFillType(e1)) {
                val oldE1WindCnt = e1.windCnt
                e1.windCnt = e2.windCnt
                e2.windCnt = oldE1WindCnt
            } else {
                if (e1.windCnt + e2.windDelta == 0) e1.windCnt = -e1.windCnt
                else e1.windCnt += e2.windDelta
                if (e2.windCnt - e1.windDelta == 0) e2.windCnt = -e2.windCnt
                else e2.windCnt -= e1.windDelta
            }
        } else {
            if (!isEvenOddFillType(e2)) e1.windCnt2 += e2.windDelta
            else e1.windCnt2 = if (e1.windCnt2 == 0) 1 else 0
            if (!isEvenOddFillType(e1)) e2.windCnt2 -= e1.windDelta
            else e2.windCnt2 = if (e2.windCnt2 == 0) 1 else 0
        }

        val e1FillType: PolyFillType?
        val e2FillType: PolyFillType?
        val e1FillType2: PolyFillType?
        val e2FillType2: PolyFillType?
        if (e1.polyTyp == PolyType.Subject) {
            e1FillType = subjFillType
            e1FillType2 = clipFillType
        } else {
            e1FillType = clipFillType
            e1FillType2 = subjFillType
        }
        if (e2.polyTyp == PolyType.Subject) {
            e2FillType = subjFillType
            e2FillType2 = clipFillType
        } else {
            e2FillType = clipFillType
            e2FillType2 = subjFillType
        }

        val e1Wc = when (e1FillType) {
            PolyFillType.Positive -> e1.windCnt
            PolyFillType.Negative -> -e1.windCnt
            else -> Math.abs(e1.windCnt)
        }
        val e2Wc = when (e2FillType) {
            PolyFillType.Positive -> e2.windCnt
            PolyFillType.Negative -> -e2.windCnt
            else -> Math.abs(e2.windCnt)
        }

        if (e1Contributing && e2Contributing) {
            if ((e1Wc != 0 && e1Wc != 1) || (e2Wc != 0 && e2Wc != 1) ||
                    (e1.polyTyp != e2.polyTyp && clipType != ClipType.Xor)) {
                addLocalMaxPoly(e1, e2, pt)
            } else {
                addOutPt(e1, pt)
                addOutPt(e2, pt)
                swapSides(e1, e2)
                swapPolyIndexes(e1, e2)
            }
        } else if (e1Contributing) {
            if (e2Wc == 0 || e2Wc == 1) {
                addOutPt(e1, pt)
                swapSides(e1, e2)
                swapPolyIndexes(e1, e2)
            }

        } else if (e2Contributing) {
            if (e1Wc == 0 || e1Wc == 1) {
                addOutPt(e2, pt)
                swapSides(e1, e2)
                swapPolyIndexes(e1, e2)
            }
        } else if ((e1Wc == 0 || e1Wc == 1) && (e2Wc == 0 || e2Wc == 1)) {
            //neither edge is currently contributing ...
            val e1Wc2 = when (e1FillType2) {
                PolyFillType.Positive -> e1.windCnt2
                PolyFillType.Negative -> -e1.windCnt2
                else -> Math.abs(e1.windCnt2)
            }
            val e2Wc2 = when (e2FillType2) {
                PolyFillType.Positive -> e2.windCnt2
                PolyFillType.Negative -> -e2.windCnt2
                else -> Math.abs(e2.windCnt2)
            }

            if (e1.polyTyp != e2.polyTyp) {
                addLocalMinPoly(e1, e2, pt)
            } else if (e1Wc == 1 && e2Wc == 1)
                when (clipType) {
                    ClipType.Intersection ->
                        if (e1Wc2 > 0 && e2Wc2 > 0)
                            addLocalMinPoly(e1, e2, pt)
                    ClipType.Union ->
                        if (e1Wc2 <= 0 && e2Wc2 <= 0)
                            addLocalMinPoly(e1, e2, pt)
                    ClipType.Difference ->
                        if (((e1.polyTyp == PolyType.Clip) && (e1Wc2 > 0) && (e2Wc2 > 0)) ||
                                ((e1.polyTyp == PolyType.Subject) && (e1Wc2 <= 0) && (e2Wc2 <= 0)))
                            addLocalMinPoly(e1, e2, pt)
                    ClipType.Xor ->
                        addLocalMinPoly(e1, e2, pt)
                }
            else
                swapSides(e1, e2)
        }
    }

    private fun deleteFromSEL(e: TEdge) {
        val selPrev = e.prevInSEL
        val selNext = e.nextInSEL
        if (selPrev == null && selNext == null && e != sortedEdges)
            return  //already deleted
        if (selPrev != null)
            selPrev.nextInSEL = selNext
        else
            sortedEdges = selNext
        selNext?.prevInSEL = selPrev
        e.nextInSEL = null
        e.prevInSEL = null
    }

    private fun processHorizontals() {
        var horzEdge: TEdge? = null //m_SortedEdges
        while (withRef<TEdge?, Boolean> { ref ->
            popEdgeFromSEL(ref).also {
                horzEdge = ref.get()
            }
        }) {
            processHorizontal(horzEdge!!)
        }
    }

    internal fun getHorzDirection(
            horzEdge: TEdge,
            dirOut: KMutableProperty0<Direction>,
            leftOut: KMutableProperty0<CInt>,
            rightOut: KMutableProperty0<CInt>) {
        if (horzEdge.bot.x < horzEdge.top.x) {
            leftOut.set(horzEdge.bot.x)
            rightOut.set(horzEdge.top.x)
            dirOut.set(Direction.LeftToRight)
        } else {
            leftOut.set(horzEdge.top.x)
            rightOut.set(horzEdge.bot.x)
            dirOut.set(Direction.RightToLeft)
        }
    }

    private fun processHorizontal(horzEdge: TEdge) {
        var horzEdge = horzEdge
        var dir: Direction? = null
        var horzLeft: CInt = ZERO
        var horzRight: CInt = ZERO
        val IsOpen = horzEdge.windDelta == 0

        withRefs<Direction, CInt, CInt, Unit> { dirOut, leftOut, rightOut ->
            getHorzDirection(horzEdge, dirOut, leftOut, rightOut)
            dir = dirOut.get()
            horzLeft = leftOut.get()
            horzRight = rightOut.get()
        }

        var eLastHorz = horzEdge
        var eMaxPair: TEdge? = null
        while (eLastHorz.nextInLML?.isHorizontal ?: false)
            eLastHorz = eLastHorz.nextInLML!!
        if (eLastHorz.nextInLML == null)
            eMaxPair = getMaximaPair(eLastHorz)

        var currMax = maxima
        if (currMax != null) {
            //get the first maxima in range (X) ...
            if (dir == Direction.LeftToRight) {
                while (currMax != null && currMax.x <= horzEdge.bot.x)
                    currMax = currMax.next
                if (currMax != null && currMax.x >= eLastHorz.top.x)
                    currMax = null
            } else {
                while (currMax!!.next?.let { it.x < horzEdge.bot.x } ?: false)
                    currMax = currMax.next
                if (currMax.x <= eLastHorz.top.x)
                    currMax = null
            }
        }

        var op1: OutPt? = null
        while (true) //loop through consec. horizontal edges
        {
            val isLastHorz = (horzEdge == eLastHorz)
            var e = getNextInAEL(horzEdge, dir!!)
            while (e != null) {
                //this code block inserts extra coords into horizontal edges (in output
                //polygons) whereever maxima touch these horizontal edges. This helps
                //'simplifying' polygons (ie if the Simplify property is set).
                if (currMax != null) {
                    if (dir == Direction.LeftToRight) {
                        while (currMax != null && currMax.x < e.curr.x) {
                            if (horzEdge.outIdx >= 0 && !IsOpen)
                                addOutPt(horzEdge, IntPoint(currMax.x, horzEdge.bot.y))
                            currMax = currMax.next
                        }
                    } else {
                        while (currMax != null && currMax.x > e.curr.x) {
                            if (horzEdge.outIdx >= 0 && !IsOpen)
                                addOutPt(horzEdge, IntPoint(currMax.x, horzEdge.bot.y))
                            currMax = currMax.prev
                        }
                    }
                }

                if ((dir == Direction.LeftToRight && e.curr.x > horzRight) ||
                        (dir == Direction.RightToLeft && e.curr.x < horzLeft)) break

                //Also break if we've got to the end of an intermediate horizontal edge ...
                //nb: Smaller Dx's are to the right of larger Dx's ABOVE the horizontal.

                if (e.curr.x == horzEdge.top.x && horzEdge.nextInLML?.let { e!!.dx < it.dx } ?: false) break

                if (horzEdge.outIdx >= 0 && !IsOpen)  //note: may be done multiple times
                {
                    op1 = addOutPt(horzEdge, e.curr)
                    var eNextHorz = sortedEdges
                    while (eNextHorz != null) {
                        if (eNextHorz.outIdx >= 0 &&
                                horzSegmentsOverlap(horzEdge.bot.x,
                                        horzEdge.top.x, eNextHorz.bot.x, eNextHorz.top.x)) {
                            val op2 = getLastOutPt(eNextHorz)
                            addJoin(op2, op1, eNextHorz.top)
                        }
                        eNextHorz = eNextHorz.nextInSEL
                    }
                    addGhostJoin(op1, horzEdge.bot)
                }

                //OK, so far we're still in range of the horizontal Edge  but make sure
                //we're at the last of consec. horizontals when matching with eMaxPair
                if (e == eMaxPair && isLastHorz) {
                    if (horzEdge.outIdx >= 0)
                        addLocalMaxPoly(horzEdge, eMaxPair, horzEdge.top)
                    deleteFromAEL(horzEdge)
                    deleteFromAEL(eMaxPair)
                    return
                }

                if (dir == Direction.LeftToRight) {
                    val pt = IntPoint(e.curr.x, horzEdge.curr.y)
                    intersectEdges(horzEdge, e, pt)
                } else {
                    val pt = IntPoint(e.curr.x, horzEdge.curr.y)
                    intersectEdges(e, horzEdge, pt)
                }
                val eNext = getNextInAEL(e, dir!!)
                swapPositionsInAEL(horzEdge, e)
                e = eNext
            } //end while(e != null)

            //Break out of loop if HorzEdge.NextInLML is not also horizontal ...

            if (!(horzEdge.nextInLML?.isHorizontal ?: false)) break

            horzEdge = withRefGet(horzEdge) { ref -> updateEdgeIntoAEL(ref) }
            if (horzEdge.outIdx >= 0) addOutPt(horzEdge, horzEdge.bot)

            withRefs<Direction, CInt, CInt, Unit> { dirOut, leftOut, rightOut ->
                getHorzDirection(horzEdge, dirOut, leftOut, rightOut)
                dir = dirOut.get()
                horzLeft = leftOut.get()
                horzRight = rightOut.get()
            }
        } //end for (;;)

        if (horzEdge.outIdx >= 0 && op1 == null) {
            op1 = getLastOutPt(horzEdge)
            var eNextHorz = sortedEdges
            while (eNextHorz != null) {
                if (eNextHorz.outIdx >= 0 &&
                        horzSegmentsOverlap(horzEdge.bot.x,
                                horzEdge.top.x, eNextHorz.bot.x, eNextHorz.top.x)) {
                    val op2 = getLastOutPt(eNextHorz)
                    addJoin(op2, op1, eNextHorz.top)
                }
                eNextHorz = eNextHorz.nextInSEL
            }
            addGhostJoin(op1, horzEdge.top)
        }

        if (horzEdge.nextInLML != null) {
            if (horzEdge.outIdx >= 0) {
                op1 = addOutPt(horzEdge, horzEdge.top)

                horzEdge = withRefGet(horzEdge) { updateEdgeIntoAEL(it) }

                if (horzEdge.windDelta == 0) return
                //nb: HorzEdge is no longer horizontal here
                val ePrev = horzEdge.prevInAEL
                val eNext = horzEdge.nextInAEL
                if (ePrev != null && ePrev.curr.x == horzEdge.bot.x &&
                        ePrev.curr.y == horzEdge.bot.y && ePrev.windDelta != 0 &&
                        (ePrev.outIdx >= 0 && ePrev.curr.y > ePrev.top.y &&
                                slopesEqual(horzEdge, ePrev, useFullRange))) {
                    val op2 = addOutPt(ePrev, horzEdge.bot)
                    addJoin(op1, op2, horzEdge.top)
                } else if (eNext != null && eNext.curr.x == horzEdge.bot.x &&
                        eNext.curr.y == horzEdge.bot.y && eNext.windDelta != 0 &&
                        eNext.outIdx >= 0 && eNext.curr.y > eNext.top.y &&
                        slopesEqual(horzEdge, eNext, useFullRange)) {
                    val op2 = addOutPt(eNext, horzEdge.bot)
                    addJoin(op1, op2, horzEdge.top)
                }
            } else
                horzEdge = withRefGet(horzEdge) { ref -> updateEdgeIntoAEL(ref) }
        } else {
            if (horzEdge.outIdx >= 0) addOutPt(horzEdge, horzEdge.top)
            deleteFromAEL(horzEdge)
        }
    }

    private fun getNextInAEL(e: TEdge, direction: Direction): TEdge? =
            if (direction == Direction.LeftToRight) e.nextInAEL else e.prevInAEL

    private fun isMinima(e: TEdge?): Boolean =
            e != null && e.prev.nextInLML != e && e.next.nextInLML != e

    // fixed y: CInt instead Double
    private fun isMaxima(e: TEdge?, y: CInt): Boolean =
            e != null && e.top.y == y && e.nextInLML == null

    // fixed bug: in original signature is -> private bool IsIntermediate(TEdge e, double Y)
    private fun isIntermediate(e: TEdge, y: CInt): Boolean {
        return e.top.y == y && e.nextInLML != null
    }

    internal fun getMaximaPair(e: TEdge): TEdge? =
            when {
                (e.next.top == e.top) && e.next.nextInLML == null -> e.next
                (e.prev.top == e.top) && e.prev.nextInLML == null -> e.prev
                else -> null
            }

    internal fun getMaximaPairEx(e: TEdge): TEdge? {
        //as above but returns null if MaxPair isn't in AEL (unless it's horizontal)
        val result = getMaximaPair(e)
        if (result == null || result.outIdx == SKIP ||
                ((result.nextInAEL == result.prevInAEL) && !result.isHorizontal)) return null
        return result
    }

    private fun processIntersections(topY: CInt): Boolean {
        if (activeEdges == null) return true
        try {
            buildIntersectList(topY)
            if (intersectList.isEmpty()) return true
            if (intersectList.size == 1 || fixupIntersectionOrder()) {
                processIntersectList()
            } else
                return false
        } catch (e: Exception) {
            sortedEdges = null
            intersectList.clear()
            throw ClipperException("ProcessIntersections error")
        }
        sortedEdges = null
        return true
    }

    private fun buildIntersectList(topY: CInt) {
        if (activeEdges == null) return

        //prepare for sorting ...
        var e = activeEdges
        sortedEdges = e
        while (e != null) {
            e.apply {
                prevInSEL = prevInAEL
                nextInSEL = nextInAEL
                curr.x = topX(e!!, topY)
            }
            e = e.nextInAEL
        }

        //bubblesort ...
        var isModified = true
        while (isModified && activeEdges != null) {
            isModified = false
            e = activeEdges
            while (e!!.nextInSEL != null) {
                val eNext = e.nextInSEL!!
                if (e.curr.x > eNext.curr.x) {
                    var pt: IntPoint
                    pt = withRefGet { ptOut -> intersectPoint(e!!, eNext, ptOut) }
                    if (pt.y < topY)
                        pt = IntPoint(topX(e, topY), topY)
                    val newNode = IntersectNode(edge1 = e, edge2 = eNext, pt = pt)
                    intersectList.add(newNode)
                    swapPositionsInSEL(e, eNext)
                    isModified = true
                } else
                    e = eNext
            }
            val ePrevInSEL = e.prevInSEL
            if (ePrevInSEL != null) ePrevInSEL.nextInSEL = null
            else break
        }
        sortedEdges = null
    }

    private fun fixupIntersectionOrder(): Boolean {
        //pre-condition: intersections are sorted bottom-most first.
        //Now it's crucial that intersections are made only between adjacent edges,
        //so to ensure this the order of intersections may need adjusting ...
        intersectList.sortWith(intersectNodeComparator)

        copyAELToSEL()
        val size = intersectList.size
        for (i in 0 until size) {
            if (!intersectList[i].hasAdjacentEdges) {
                var j = i + 1
                while (j < size && !intersectList[j].hasAdjacentEdges) j++
                if (j == size) return false

                val tmp = intersectList[i]
                intersectList[i] = intersectList[j]
                intersectList[j] = tmp

            }
            swapPositionsInSEL(intersectList[i].edge1, intersectList[i].edge2)
        }
        return true
    }

    private fun processIntersectList() {
        intersectList.forEach { iNode ->
            intersectEdges(iNode.edge1, iNode.edge2, iNode.pt)
            swapPositionsInAEL(iNode.edge1, iNode.edge2)
        }
        intersectList.clear()
    }

    private fun intersectPoint(edge1: TEdge, edge2: TEdge, ipOut: Out<IntPoint>) {
        val ip = IntPoint()
        ipOut.set(ip)
        val b1: Double
        val b2: Double
        //nb: with very large coordinate values, it's possible for SlopesEqual() to
        //return false but for the edge.Dx value be equal due to double precision rounding.
        if (edge1.dx == edge2.dx) {
            ip.y = edge1.curr.y
            ip.x = topX(edge1, ip.y)
            return
        }

        when (ZERO) {
            edge1.delta.x -> {
                ip.x = edge1.bot.x
                ip.y = if (edge2.isHorizontal) {
                    edge2.bot.y
                } else {
                    b2 = edge2.bot.y - (edge2.bot.x / edge2.dx)
                    round(ip.x / edge2.dx + b2)
                }
            }
            edge2.delta.x -> {
                ip.x = edge2.bot.x
                ip.y = if (edge1.isHorizontal) {
                    edge1.bot.y
                } else {
                    b1 = edge1.bot.y - (edge1.bot.x / edge1.dx)
                    round(ip.x / edge1.dx + b1)
                }
            }
            else -> {
                b1 = edge1.bot.x - edge1.bot.y * edge1.dx
                b2 = edge2.bot.x - edge2.bot.y * edge2.dx
                val q = (b2 - b1) / (edge1.dx - edge2.dx)
                ip.y = round(q)
                ip.x = if (Math.abs(edge1.dx) < Math.abs(edge2.dx)) round(edge1.dx * q + b1) else round(edge2.dx * q + b2)
            }
        }

        if (ip.y < edge1.top.y || ip.y < edge2.top.y) {
            ip.y = if (edge1.top.y > edge2.top.y) edge1.top.y else edge2.top.y
            ip.x = if (Math.abs(edge1.dx) < Math.abs(edge2.dx)) topX(edge1, ip.y) else topX(edge2, ip.y)
        }
        //finally, don't allow 'ip' to be BELOW curr.Y (ie bottom of scanbeam) ...
        if (ip.y > edge1.curr.y) {
            ip.y = edge1.curr.y
            //better to use the more vertical edge to derive X ...
            ip.x = if (Math.abs(edge1.dx) > Math.abs(edge2.dx)) topX(edge2, ip.y) else topX(edge1, ip.y)
        }
    }

    private fun processEdgesAtTopOfScanbeam(topY: CInt) {
        var e = activeEdges
        while (e != null) {
            //1. process maxima, treating them as if they're 'bent' horizontal edges,
            //   but exclude maxima with horizontal edges. nb: e can't be a horizontal.
            var isMaximaEdge = isMaxima(e, topY)

            if (isMaximaEdge) {
                val eMaxPair = getMaximaPairEx(e)
                isMaximaEdge = (eMaxPair == null || !eMaxPair.isHorizontal)
            }

            if (isMaximaEdge) {
                if (strictlySimple) insertMaxima(e.top.x)
                val ePrev = e.prevInAEL
                doMaxima(e)
                if (ePrev == null) e = activeEdges
                else e = ePrev.nextInAEL
            } else {
                //2. promote horizontal edges, otherwise update Curr.X and Curr.Y ...
                if (isIntermediate(e, topY) && e.nextInLML!!.isHorizontal) {
                    e = withRefGet(e) { ref -> updateEdgeIntoAEL(ref) }
                    if (e.outIdx >= 0)
                        addOutPt(e, e.bot)
                    addEdgeToSEL(e)
                } else {
                    e.curr.x = topX(e, topY)
                    e.curr.y = topY
                }
                //When StrictlySimple and 'e' is being touched by another edge, then
                //make sure both edges have a vertex here ...
                if (strictlySimple) {
                    val ePrev = e.prevInAEL
                    if ((e.outIdx >= 0) && (e.windDelta != 0) && ePrev != null &&
                            (ePrev.outIdx >= 0) && (ePrev.curr.x == e.curr.x) &&
                            (ePrev.windDelta != 0)) {
                        val ip = e.curr
                        val op = addOutPt(ePrev, ip)
                        val op2 = addOutPt(e, ip)
                        addJoin(op, op2, ip) //StrictlySimple (type-3) join
                    }
                }

                e = e.nextInAEL
            }
        }

        //3. Process horizontals at the Top of the scanbeam ...
        processHorizontals()
        maxima = null

        //4. Promote intermediate vertices ...
        e = activeEdges
        while (e != null) {
            if (isIntermediate(e, topY)) {
                var op: OutPt? = null
                if (e.outIdx >= 0)
                    op = addOutPt(e, e.top)
                e = withRefGet(e) { ref -> updateEdgeIntoAEL(ref) }
                //if output polygons share an edge, they'll need joining later ...
                val ePrev = e.prevInAEL
                val eNext = e.nextInAEL
                if (ePrev != null && ePrev.curr.x == e.bot.x &&
                        ePrev.curr.y == e.bot.y && op != null &&
                        ePrev.outIdx >= 0 && ePrev.curr.y > ePrev.top.y &&
                        slopesEqual(e.curr, e.top, ePrev.curr, ePrev.top, useFullRange) &&
                        (e.windDelta != 0) && (ePrev.windDelta != 0)) {
                    val op2 = addOutPt(ePrev, e.bot)
                    addJoin(op, op2, e.top)
                } else if (eNext != null && eNext.curr.x == e.bot.x &&
                        eNext.curr.y == e.bot.y && op != null &&
                        eNext.outIdx >= 0 && eNext.curr.y > eNext.top.y &&
                        slopesEqual(e.curr, e.top, eNext.curr, eNext.top, useFullRange) &&
                        (e.windDelta != 0) && (eNext.windDelta != 0)) {
                    val op2 = addOutPt(eNext, e.bot)
                    addJoin(op, op2, e.top)
                }
            }
            e = e.nextInAEL
        }
    }

    private fun doMaxima(e: TEdge) {
        val eMaxPair = getMaximaPairEx(e)
        if (eMaxPair == null) {
            if (e.outIdx >= 0)
                addOutPt(e, e.top)
            deleteFromAEL(e)
            return
        }

        var eNext = e.nextInAEL
        while (eNext != null && eNext != eMaxPair) {
            intersectEdges(e, eNext, e.top)
            swapPositionsInAEL(e, eNext)
            eNext = e.nextInAEL
        }

        if (e.outIdx == UNASSIGNED && eMaxPair.outIdx == UNASSIGNED) {
            deleteFromAEL(e)
            deleteFromAEL(eMaxPair)
        } else if (e.outIdx >= 0 && eMaxPair.outIdx >= 0) {
            if (e.outIdx >= 0) addLocalMaxPoly(e, eMaxPair, e.top)
            deleteFromAEL(e)
            deleteFromAEL(eMaxPair)
        } else if (USE_LINES && e.windDelta == 0) {
            if (e.outIdx >= 0) {
                addOutPt(e, e.top)
                e.outIdx = UNASSIGNED
            }
            deleteFromAEL(e)

            if (eMaxPair.outIdx >= 0) {
                addOutPt(eMaxPair, e.top)
                eMaxPair.outIdx = UNASSIGNED
            }
            deleteFromAEL(eMaxPair)
        } else throw ClipperException("DoMaxima error")
    }

    private fun pointCount(pts: OutPt?): Int {
        if (pts == null) return 0
        var result = 0
        var p: OutPt = pts
        do {
            result++
            p = p.next
        } while (p != pts)
        return result
    }

    private fun buildResult(polyg: Paths) {
        polyg.clear()
        // polyg.capacity = m_PolyOuts.count
        for (outRec in polyOuts) {
            var p = outRec.pts?.prev ?: continue
            val cnt = pointCount(p)
            if (cnt < 2) continue
            val pg = Path(cnt)
            for (j in 0..cnt - 1) {
                pg.add(p.pt)
                p = p.prev
            }
            polyg.add(pg)
        }
    }

    private fun buildResult2(polytree: PolyTree) {
        polytree.clear()

        //add each output polygon/contour to polytree ...
        //polytree.m_AllPolys.Capacity = m_PolyOuts.Count
        for (outRec in polyOuts) {
            val cnt = pointCount(outRec.pts)
            if ((outRec.isOpen && cnt < 2) ||
                    (!outRec.isOpen && cnt < 3)) continue
            fixHoleLinkage(outRec)
            val pn = PolyNode(jointype = null, endtype = null)
            polytree.allPolys.add(pn)
            outRec.polyNode = pn
            //pn.m_polygon.Capacity = cnt
            var op = outRec.pts!!.prev
            (0 until cnt).forEach {
                pn.contour.add(op.pt)
                op = op.prev
            }
        }

        //fixup PolyNode links etc ...
        //polytree.m_Childs.Capacity = m_PolyOuts.Count
        for (outRec in polyOuts) {
            val outRecPolyNode = outRec.polyNode ?: continue
            if (outRec.isOpen) {
                outRecPolyNode.isOpen = true
                polytree.addChild(outRecPolyNode)
            } else {
                outRec.firstLeft?.polyNode?.addChild(outRecPolyNode) ?: polytree.addChild(outRecPolyNode)
            }
        }
    }

    private fun fixupOutPolyline(outrec: OutRec) {
        var pp = outrec.pts!!
        var lastPP = pp.prev
        while (pp != lastPP) {
            pp = pp.next
            if (pp.pt == pp.prev.pt) {
                if (pp == lastPP) lastPP = pp.prev
                val tmpPP = pp.prev
                tmpPP.next = pp.next
                pp.next.prev = tmpPP
                pp = tmpPP
            }
        }
        if (pp == pp.prev) outrec.pts = null
    }

    private fun fixupOutPolygon(outRec: OutRec) {
        //FixupOutPolygon() - removes duplicate points and simplifies consecutive
        //parallel edges by removing the middle vertex.
        var lastOK: OutPt? = null
        outRec.bottomPt = null
        var pp = outRec.pts!!
        val preserveCol = preserveCollinear || strictlySimple
        while (true) {
            if (pp.prev == pp || pp.prev == pp.next) {
                outRec.pts = null
                return
            }
            //test for duplicate points and collinear edges ...
            if (pp.pt == pp.next.pt || pp.pt == pp.prev.pt
                    || (slopesEqual(pp.prev.pt, pp.pt, pp.next.pt, useFullRange)
                    && (!preserveCol || !pt2IsBetweenPt1AndPt3(pp.prev.pt, pp.pt, pp.next.pt)))) {
                lastOK = null
                pp.prev.next = pp.next
                pp.next.prev = pp.prev
                pp = pp.prev
            } else if (pp == lastOK) {
                break
            } else {
                if (lastOK == null) lastOK = pp
                pp = pp.next
            }
        }
        outRec.pts = pp
    }

    internal fun dupOutPt(outPt: OutPt, insertAfter: Boolean): OutPt {
        val result = OutPt(idx = outPt.idx, pt = outPt.pt)
        if (insertAfter) {
            result.next = outPt.next
            result.prev = outPt
            outPt.next.prev = result
            outPt.next = result
        } else {
            result.prev = outPt.prev
            result.next = outPt
            outPt.prev.next = result
            outPt.prev = result
        }
        return result
    }

    fun getOverlap(a1: CInt, a2: CInt, b1: CInt, b2: CInt, leftOut: Out<CInt>, rightOut: Out<CInt>): Boolean {
        val left: CInt
        val right: CInt
        if (a1 < a2) {
            if (b1 < b2) {
                left = Math.max(a1, b1); right = Math.min(a2, b2)
            } else {
                left = Math.max(a1, b2); right = Math.min(a2, b1)
            }
        } else {
            if (b1 < b2) {
                left = Math.max(a2, b1); right = Math.min(a1, b2)
            } else {
                left = Math.max(a2, b2); right = Math.min(a1, b1)
            }
        }
        leftOut.set(left)
        rightOut.set(right)
        return left < right
    }

    internal fun joinHorz(op1: OutPt, op1b: OutPt, op2: OutPt, op2b: OutPt,
                          pt: IntPoint, discardLeft: Boolean): Boolean {
        var op1 = op1
        var op1b = op1b
        var op2 = op2
        var op2b = op2b
        val dir1 = if (op1.pt.x > op1b.pt.x) Direction.RightToLeft else Direction.LeftToRight
        val dir2 = if (op2.pt.x > op2b.pt.x) Direction.RightToLeft else Direction.LeftToRight
        if (dir1 == dir2) return false

        //When discardLeft, we want Op1b to be on the Left of Op1, otherwise we
        //want Op1b to be on the Right. (And likewise with Op2 and Op2b.)
        //So, to facilitate this while inserting Op1b and Op2b ...
        //when discardLeft, make sure we're AT or RIGHT of pt before adding Op1b,
        //otherwise make sure we're AT or LEFT of pt. (Likewise with Op2b.)
        if (dir1 == Direction.LeftToRight) {
            while (op1.next.pt.x <= pt.x &&
                    op1.next.pt.x >= op1.pt.x && op1.next.pt.y == pt.y)
                op1 = op1.next
            if (discardLeft && op1.pt.x != pt.x) op1 = op1.next
            op1b = dupOutPt(op1, !discardLeft)
            if (op1b.pt != pt) {
                op1 = op1b
                op1.pt set pt
                op1b = dupOutPt(op1, !discardLeft)
            }
        } else {
            while (op1.next.pt.x >= pt.x &&
                    op1.next.pt.x <= op1.pt.x && op1.next.pt.y == pt.y)
                op1 = op1.next
            if (!discardLeft && op1.pt.x != pt.x) op1 = op1.next
            op1b = dupOutPt(op1, discardLeft)
            if (op1b.pt != pt) {
                op1 = op1b
                op1.pt set pt
                op1b = dupOutPt(op1, discardLeft)
            }
        }

        if (dir2 == Direction.LeftToRight) {
            while (op2.next.pt.x <= pt.x &&
                    op2.next.pt.x >= op2.pt.x && op2.next.pt.y == pt.y)
                op2 = op2.next
            if (discardLeft && op2.pt.x != pt.x) op2 = op2.next
            op2b = dupOutPt(op2, !discardLeft)
            if (op2b.pt != pt) {
                op2 = op2b
                op2.pt set pt
                op2b = dupOutPt(op2, !discardLeft)
            }
        } else {
            while (op2.next.pt.x >= pt.x &&
                    op2.next.pt.x <= op2.pt.x && op2.next.pt.y == pt.y)
                op2 = op2.next
            if (!discardLeft && op2.pt.x != pt.x) op2 = op2.next
            op2b = dupOutPt(op2, discardLeft)
            if (op2b.pt != pt) {
                op2 = op2b
                op2.pt set pt
                op2b = dupOutPt(op2, discardLeft)
            }
        }

        if (dir1 == Direction.LeftToRight == discardLeft) {
            op1.prev = op2
            op2.next = op1
            op1b.next = op2b
            op2b.prev = op1b
        } else {
            op1.next = op2
            op2.prev = op1
            op1b.prev = op2b
            op2b.next = op1b
        }
        return true
    }

    private fun joinPoints(j: Join, outRec1: OutRec, outRec2: OutRec): Boolean {
        var op1 = j.outPt1!!
        var op1b: OutPt
        var op2 = j.outPt2!!
        var op2b: OutPt

        //There are 3 kinds of joins for output polygons ...
        //1. Horizontal joins where Join.OutPt1 & Join.outPt2 are vertices anywhere
        //along (horizontal) collinear edges (& Join.OffPt is on the same horizontal).
        //2. Non-horizontal joins where Join.OutPt1 & Join.outPt2 are at the same
        //location at the Bottom of the overlapping segment (& Join.OffPt is above).
        //3. StrictlySimple joins where edges touch but are not collinear and where
        //Join.OutPt1, Join.outPt2 & Join.OffPt all share the same point.
        val isHorizontal = (op1.pt.y == j.offPt.y)

        if (isHorizontal && (j.offPt == op1.pt) && (j.offPt == op2.pt)) {
            //Strictly Simple join ...
            if (outRec1 != outRec2) return false
            op1b = op1.next
            while (op1b != op1 && (op1b.pt == j.offPt))
                op1b = op1b.next
            val reverse1 = (op1b.pt.y > j.offPt.y)
            op2b = op2.next
            while (op2b != op2 && (op2b.pt == j.offPt))
                op2b = op2b.next
            val reverse2 = (op2b.pt.y > j.offPt.y)
            if (reverse1 == reverse2) return false
            if (reverse1) {
                op1b = dupOutPt(op1, false)
                op2b = dupOutPt(op2, true)
                op1.prev = op2
                op2.next = op1
                op1b.next = op2b
                op2b.prev = op1b
                j.outPt1 = op1
                j.outPt2 = op1b
                return true
            } else {
                op1b = dupOutPt(op1, true)
                op2b = dupOutPt(op2, false)
                op1.next = op2
                op2.prev = op1
                op1b.prev = op2b
                op2b.next = op1b
                j.outPt1 = op1
                j.outPt2 = op1b
                return true
            }
        } else if (isHorizontal) {
            //treat horizontal joins differently to non-horizontal joins since with
            //them we're not yet sure where the overlapping is. outPt1.pt & outPt2.pt
            //may be anywhere along the horizontal edge.
            op1b = op1
            while (op1.prev.pt.y == op1.pt.y && op1.prev != op1b && op1.prev != op2)
                op1 = op1.prev
            while (op1b.next.pt.y == op1b.pt.y && op1b.next != op1 && op1b.next != op2)
                op1b = op1b.next
            if (op1b.next == op1 || op1b.next == op2) return false; //a flat 'polygon'

            op2b = op2
            while (op2.prev.pt.y == op2.pt.y && op2.prev != op2b && op2.prev != op1b)
                op2 = op2.prev
            while (op2b.next.pt.y == op2b.pt.y && op2b.next != op2 && op2b.next != op1)
                op2b = op2b.next
            if (op2b.next == op2 || op2b.next == op1) return false; //a flat 'polygon'

            var left: CInt = ZERO
            var right: CInt = ZERO
            //Op1 -. Op1b & Op2 -. Op2b are the extremites of the horizontal edges

            if (!withRefs<CInt, CInt, Boolean> { leftOut, rightOut ->
                getOverlap(op1.pt.x, op1b.pt.x, op2.pt.x, op2b.pt.x, leftOut, rightOut).also {
                    left = leftOut.get()
                    right = rightOut.get()
                }
            }) {
                return false
            }

            //DiscardLeftSide: when overlapping edges are joined, a spike will created
            //which needs to be cleaned up. However, we don't want Op1 or Op2 caught up
            //on the discard Side as either may still be needed for other joins ...
            val pt: IntPoint = IntPoint()
            val discardLeftSide: Boolean
            when {
                op1.pt.x in left..right -> {
                    pt set op1.pt; discardLeftSide = (op1.pt.x > op1b.pt.x)
                }
                op2.pt.x in left..right -> {
                    pt set op2.pt; discardLeftSide = (op2.pt.x > op2b.pt.x)
                }
                op1b.pt.x in left..right -> {
                    pt set op1b.pt; discardLeftSide = op1b.pt.x > op1.pt.x
                }
                else -> {
                    pt set op2b.pt; discardLeftSide = (op2b.pt.x > op2.pt.x)
                }
            }
            j.outPt1 = op1
            j.outPt2 = op2
            return joinHorz(op1, op1b, op2, op2b, pt, discardLeftSide)
        } else {
            //nb: For non-horizontal joins ...
            //    1. Jr.outPt1.pt.y == Jr.outPt2.pt.y
            //    2. Jr.outPt1.pt > Jr.offPt.y

            //make sure the polygons are correctly oriented ...
            op1b = op1.next
            while ((op1b.pt == op1.pt) && (op1b != op1)) op1b = op1b.next
            val reverse1 = ((op1b.pt.y > op1.pt.y) ||
                    !slopesEqual(op1.pt, op1b.pt, j.offPt, useFullRange))
            if (reverse1) {
                op1b = op1.prev
                while ((op1b.pt == op1.pt) && (op1b != op1)) op1b = op1b.prev
                if ((op1b.pt.y > op1.pt.y) ||
                        !slopesEqual(op1.pt, op1b.pt, j.offPt, useFullRange)) return false
            }
            op2b = op2.next
            while ((op2b.pt == op2.pt) && (op2b != op2)) op2b = op2b.next
            val reverse2 = ((op2b.pt.y > op2.pt.y) ||
                    !slopesEqual(op2.pt, op2b.pt, j.offPt, useFullRange))
            if (reverse2) {
                op2b = op2.prev
                while ((op2b.pt == op2.pt) && (op2b != op2)) op2b = op2b.prev
                if ((op2b.pt.y > op2.pt.y) ||
                        !slopesEqual(op2.pt, op2b.pt, j.offPt, useFullRange)) return false
            }

            if ((op1b == op1) || (op2b == op2) || (op1b == op2b) ||
                    ((outRec1 == outRec2) && (reverse1 == reverse2))) return false

            if (reverse1) {
                op1b = dupOutPt(op1, false)
                op2b = dupOutPt(op2, true)
                op1.prev = op2
                op2.next = op1
                op1b.next = op2b
                op2b.prev = op1b
                j.outPt1 = op1
                j.outPt2 = op1b
                return true
            } else {
                op1b = dupOutPt(op1, true)
                op2b = dupOutPt(op2, false)
                op1.next = op2
                op2.prev = op1
                op1b.prev = op2b
                op2b.next = op1b
                j.outPt1 = op1
                j.outPt2 = op1b
                return true
            }
        }
    }


    private fun fixupFirstLefts1(oldOutRec: OutRec, newOutRec: OutRec) {
        for (outRec in polyOuts) {
            val firstLeft = parseFirstLeft(outRec.firstLeft)
            if (outRec.pts != null && firstLeft == oldOutRec) {
                if (newOutRec.pts!!.containsPoint(outRec.pts!!))
                    outRec.firstLeft = newOutRec
            }
        }
    }

    private fun fixupFirstLefts2(innerOutRec: OutRec, outerOutRec: OutRec) {
        //A polygon has split into two such that one is now the inner of the other.
        //It's possible that these polygons now wrap around other polygons, so check
        //every polygon that's also contained by OuterOutRec's FirstLeft container
        //(including nil) to see if they've become inner to the new inner polygon ...
        val orfl = outerOutRec.firstLeft
        for (outRec in polyOuts) {
            if (outRec.pts == null || outRec == outerOutRec || outRec == innerOutRec)
                continue
            val firstLeft = parseFirstLeft(outRec.firstLeft)
            if (firstLeft != orfl && firstLeft != innerOutRec && firstLeft != outerOutRec)
                continue
            if (innerOutRec.pts!!.containsPoint(outRec.pts!!))
                outRec.firstLeft = innerOutRec
            else if (outerOutRec.pts!!.containsPoint(outRec.pts!!))
                outRec.firstLeft = outerOutRec
            else if (outRec.firstLeft == innerOutRec || outRec.firstLeft == outerOutRec)
                outRec.firstLeft = orfl
        }
    }

    private fun fixupFirstLefts3(oldOutRec: OutRec, newOutRec: OutRec) {
        //same as FixupFirstLefts1 but doesn't call Poly2ContainsPoly1()
        for (outRec in polyOuts) {
            val firstLeft = parseFirstLeft(outRec.firstLeft)
            if (outRec.pts != null && firstLeft == oldOutRec)
                outRec.firstLeft = newOutRec
        }
    }

    private fun joinCommonEdges() {
        for (join in joins) {
            val outRec1 = getOutRec(join.outPt1!!.idx)
            var outRec2 = getOutRec(join.outPt2!!.idx)

            if (outRec1.pts == null || outRec2.pts == null) continue
            if (outRec1.isOpen || outRec2.isOpen) continue

            //get the polygon fragment with the correct hole state (FirstLeft)
            //before calling JoinPoints() ...
            val holeStateRec = when {
                outRec1 == outRec2 -> outRec1
                outRec1.isRightOf(outRec2) -> outRec2
                outRec2.isRightOf(outRec1) -> outRec1
                else -> getLowermostRec(outRec1, outRec2)
            }

            if (!joinPoints(join, outRec1, outRec2)) continue

            if (outRec1 == outRec2) {
                //instead of joining two polygons, we've just created a new one by
                //splitting one polygon into two.
                outRec1.pts = join.outPt1
                outRec1.bottomPt = null
                outRec2 = createOutRec()
                outRec2.pts = join.outPt2

                //update all OutRec2.pts idx's ...
                outRec2.updateOutPtIdxs()

                if (outRec1.pts!!.containsPoint(outRec2.pts!!)) {
                    //outRec1 containsPoint outRec2 ...
                    outRec2.isHole = !outRec1.isHole
                    outRec2.firstLeft = outRec1

                    if (usingPolyTree) fixupFirstLefts2(outRec2, outRec1)

                    if (outRec2.isHole xor reverseSolution == outRec2.calcArea() > 0)
                        outRec2.pts?.reversePolyPtLinks()

                } else if (outRec2.pts!!.containsPoint(outRec1.pts!!)) {
                    //outRec2 containsPoint outRec1 ...
                    outRec2.isHole = outRec1.isHole
                    outRec1.isHole = !outRec2.isHole
                    outRec2.firstLeft = outRec1.firstLeft
                    outRec1.firstLeft = outRec2

                    if (usingPolyTree) fixupFirstLefts2(outRec1, outRec2)

                    if (outRec1.isHole xor reverseSolution == outRec1.calcArea() > 0)
                        outRec1.pts?.reversePolyPtLinks()
                } else {
                    //the 2 polygons are completely separate ...
                    outRec2.isHole = outRec1.isHole
                    outRec2.firstLeft = outRec1.firstLeft

                    //fixup firstLeft pointers that may need reassigning to OutRec2
                    if (usingPolyTree) fixupFirstLefts1(outRec1, outRec2)
                }

            } else {
                //joined 2 polygons together ...

                outRec2.pts = null
                outRec2.bottomPt = null
                outRec2.idx = outRec1.idx

                outRec1.isHole = holeStateRec.isHole
                if (holeStateRec == outRec2)
                    outRec1.firstLeft = outRec2.firstLeft
                outRec2.firstLeft = outRec1

                //fixup firstLeft pointers that may need reassigning to OutRec1
                if (usingPolyTree) fixupFirstLefts3(outRec2, outRec1)
            }
        }
    }

    private fun doSimplePolygons() {
        var i = 0
        while (i < polyOuts.size) {
            val outrec = polyOuts[i]
            i++
            var op = outrec.pts
            if (op == null || outrec.isOpen) continue
            do
            //for each Pt in Polygon until duplicate found do ...
            {
                var op2 = op!!.next
                while (op2 != outrec.pts) {
                    if (op.pt == op2.pt && op2.next != op && op2.prev != op) {
                        //split the polygon into two ...
                        val op3 = op.prev
                        val op4 = op2.prev
                        op.prev = op4
                        op4.next = op
                        op2.prev = op3
                        op3.next = op2

                        outrec.pts = op
                        val outrec2 = createOutRec()
                        outrec2.pts = op2
                        outrec2.updateOutPtIdxs()
                        if (outrec.pts!!.containsPoint(outrec2.pts!!)) {
                            //OutRec2 is contained by OutRec1 ...
                            outrec2.isHole = !outrec.isHole
                            outrec2.firstLeft = outrec
                            if (usingPolyTree) fixupFirstLefts2(outrec2, outrec)
                        } else if (outrec2.pts!!.containsPoint(outrec.pts!!)) {
                            //OutRec1 is contained by OutRec2 ...
                            outrec2.isHole = outrec.isHole
                            outrec.isHole = !outrec2.isHole
                            outrec2.firstLeft = outrec.firstLeft
                            outrec.firstLeft = outrec2
                            if (usingPolyTree) fixupFirstLefts2(outrec, outrec2)
                        } else {
                            //the 2 polygons are separate ...
                            outrec2.isHole = outrec.isHole
                            outrec2.firstLeft = outrec.firstLeft
                            if (usingPolyTree) fixupFirstLefts1(outrec, outrec2)
                        }
                        op2 = op //ie get ready for the next iteration
                    }
                    op2 = op2.next
                }
                op = op.next
            } while (op != outrec.pts)
        }
    }
} //end Clipper

fun getUnitNormal(pt1: IntPoint, pt2: IntPoint): DoublePoint {
    val dx = pt2.x - pt1.x
    val dy = pt2.y - pt1.y
    if (dx == ZERO && dy == ZERO) return DoublePoint()

    val f = 1 * 1.0 / Math.sqrt(dx.toDouble() * dx.toDouble() + dy.toDouble() * dy.toDouble())

    return DoublePoint(dy * f, -dx * f)
}

class ClipperOffset(
        var miterLimit: Double = 2.0,
        var arcTolerance: Double = ClipperOffset.DEF_ARC_TOLERANCE
) {
    companion object {
        const val TWO_PI = Math.PI * 2.0
        const val DEF_ARC_TOLERANCE = 0.25
    }

    private lateinit var destPolys: Paths
    private lateinit var srcPoly: Path
    private lateinit var destPoly: Path
    private val normals = ArrayList<DoublePoint>()
    private var delta: Double = 0.0
    private var sinA: Double = 0.0
    private var sin: Double = 0.0
    private var cos: Double = 0.0
    private var miterLim: Double = 0.0
    private var stepsPerRad: Double = 0.0
    private val lowest: IntPoint = IntPoint(-1, 0)
    private val polyNodes: PolyNode = PolyNode()

    fun clear() {
        polyNodes.childs.clear()
        lowest.x = -1
    }

    fun addPath(path: Path, joinType: JoinType, endType: EndType) {
        var highI = path.size - 1
        if (highI < 0) return
        val newNode = PolyNode(jointype = joinType, endtype = endType)

        val polygon = newNode.contour

        //strip duplicate points from path and also get index to the lowest point ...
        if (endType == EndType.ClosedLine || endType == EndType.ClosedPolygon)
            while (highI > 0 && path[0] == path[highI]) highI--
        (polygon as ArrayList<IntPoint>).ensureCapacity(highI + 1)
        polygon += path[0]
        var j = 0
        var k = 0
        path.forEach { pt ->
            if (polygon[j] != pt) {
                j++
                polygon += pt
                if (pt.y > polygon[k].y || (pt.y == polygon[k].y && pt.x < polygon[k].x))
                    k = j
            }
        }
        if (endType == EndType.ClosedPolygon && j < 2) return

        polyNodes.addChild(newNode)

        //if this path's lowest pt is lower than all the others then update m_lowest
        if (endType != EndType.ClosedPolygon) return
        if (lowest.x < 0)
            lowest.set((polyNodes.childCount - 1).toLong(), k.toLong())
        else {
            val ip = polyNodes.childs[lowest.x.toInt()].contour[lowest.y.toInt()]
            val polygonK = polygon[k]
            if (polygonK.y > ip.y || (polygonK.y == ip.y && polygonK.x < ip.x))
                lowest.set((polyNodes.childCount - 1).toLong(), k.toLong())
        }
    }

    fun addPaths(paths: Paths, joinType: JoinType, endType: EndType) {
        for (p in paths) {
            addPath(p, joinType, endType)
        }
    }

    private fun fixOrientations() {
        //fixup orientations of all closed paths if the orientation of the
        //closed path with the lowermost vertex is wrong ...
        if (lowest.x >= 0 && !polyNodes.childs[lowest.x.toInt()].contour.orientation) {
            polyNodes.childs.forEach { node ->
                if (node.endtype == EndType.ClosedPolygon || (node.endtype == EndType.ClosedLine && node.contour.orientation))
                    node.contour.reverse()
            }
        } else {
            polyNodes.childs.forEach { node ->
                if (node.endtype == EndType.ClosedLine && !node.contour.orientation)
                    node.contour.reverse()
            }
        }
    }

    private fun doOffset(delta: Double) {
        this.delta = delta
        //if Zero offset, just copy any CLOSED polygons to m_p and return ...
        if (delta.isNearZero) {
            destPolys = Paths(polyNodes.childCount)
            polyNodes.childs.forEach { node ->
                if (node.endtype == EndType.ClosedPolygon)
                    destPolys.add(node.contour)
            }
            return
        }
        //see offset_triginometry3.svg in the documentation folder ...
        miterLim = if (miterLimit > 2) 2 / (miterLimit * miterLimit) else 0.5
        //see offset_triginometry2.svg in the documentation folder ...
        val steps = when {
            arcTolerance <= 0.0 -> DEF_ARC_TOLERANCE
            arcTolerance > Math.abs(delta) * DEF_ARC_TOLERANCE -> Math.abs(delta) * DEF_ARC_TOLERANCE
            else -> arcTolerance
        }.let { y ->
            Math.PI / Math.acos(1 - y / Math.abs(delta))
        }
        sin = Math.sin(TWO_PI / steps)
        cos = Math.cos(TWO_PI / steps)
        stepsPerRad = steps / TWO_PI
        if (delta < 0.0) sin = -sin
        destPolys = Paths(polyNodes.childCount * 2)

        for (node in polyNodes.childs) {
            srcPoly = node.contour
            val len = srcPoly.size
            if (len == 0 || (delta <= 0 && (len < 3 || node.endtype != EndType.ClosedPolygon)))
                continue
            destPoly = pathOf()
            if (len == 1) {
                val srcPt = srcPoly[0]
                if (node.jointype == JoinType.Round) {
                    var x = 1.0
                    var y = 0.0
                    var j = 1
                    while (j <= steps) {
                        destPoly.add(IntPoint(
                                round(srcPt.x + x * delta),
                                round(srcPt.y + y * delta)))
                        val x2 = x
                        x = x * cos - sin * y
                        y = x2 * sin + y * cos
                        j++
                    }
                } else {
                    var x = -1.0
                    var y = -1.0
                    (1..4).forEach {
                        destPoly.add(IntPoint(
                                round(srcPt.x + x * delta),
                                round(srcPt.y + y * delta)))
                        if (x < 0)
                            x = 1.0
                        else if (y < 0)
                            y = 1.0
                        else
                            x = -1.0
                    }
                }
                destPolys.add(destPoly)
                continue
            }
            //build m_normals ...
            normals.clear()
            normals.ensureCapacity(len)
            (0 until len - 1).mapTo(normals) { getUnitNormal(srcPoly[it], srcPoly[it + 1]) }
            if ((node.endtype == EndType.ClosedLine || node.endtype == EndType.ClosedPolygon))
                normals.add(getUnitNormal(srcPoly[len - 1], srcPoly[0]))
            else
                normals.add(normals[len - 2].copy()) // DoublePoint(m_normals[len - 2])

            if (node.endtype == EndType.ClosedPolygon) {
                var k = len - 1
                for (j in 0 until len) {
                    k = withRefGet(k) { ref -> offsetPoint(j, ref, node.jointype!!) }
                }
                destPolys.add(destPoly)
            } else if (node.endtype == EndType.ClosedLine) {
                var k = len - 1
                for (j in 0 until len) {
                    k = withRefGet(k) { ref -> offsetPoint(j, ref, node.jointype!!) }
                }
                destPolys.add(destPoly)
                destPoly = pathOf()
                //re-build m_normals ...
                val n = normals[len - 1]
                for (j in len - 1 downTo 1)
                    normals[j] = DoublePoint(-normals[j - 1].x, -normals[j - 1].y)
                normals[0] = DoublePoint(-n.x, -n.y)
                k = 0
                for (j in len - 1 downTo 0) {
                    k = withRefGet(k) { ref -> offsetPoint(j, ref, node.jointype!!) }
                }
                destPolys.add(destPoly)
            } else {
                var k = 0
                for (j in 1 until len - 1) {
                    k = withRefGet(k) { ref -> offsetPoint(j, ref, node.jointype!!) }
                }
                if (node.endtype == EndType.OpenButt) {
                    val j = len - 1
                    val srcPolyJ = srcPoly[j]
                    val normalsJ = normals[j]
                    destPoly.add(IntPoint(
                            x = round(srcPolyJ.x + normalsJ.x * delta),
                            y = round(srcPolyJ.y + normalsJ.y * delta)))
                    destPoly.add(IntPoint(
                            x = round(srcPolyJ.x - normalsJ.x * delta),
                            y = round(srcPolyJ.y - normalsJ.y * delta)))
                } else {
                    val j = len - 1
                    k = len - 2
                    sinA = 0.0
                    val normalsJ = normals[j]
                    normals[j] = DoublePoint(-normalsJ.x, -normalsJ.y)
                    if (node.endtype == EndType.OpenSquare)
                        doSquare(j, k)
                    else
                        doRound(j, k)
                }
                //re-build m_normals ...
                for (j in len - 1 downTo 1) {
                    normals[j - 1].let { normals[j] = DoublePoint(-it.x, -it.y) }
                }
                normals[1].let { normals[0] = DoublePoint(-it.x, -it.y) }

                k = len - 1
                for (j in k - 1 downTo 1) {
                    k = withRefGet(k) { ref -> offsetPoint(j, ref, node.jointype!!) }
                }
                if (node.endtype == EndType.OpenButt) {
                    val srcPoly0 = srcPoly[0]
                    val normals0 = normals[0]
                    destPoly.add(IntPoint(
                            x = round(srcPoly0.x - normals0.x * delta),
                            y = round(srcPoly0.y - normals0.y * delta)))
                    destPoly.add(IntPoint(
                            x = round(srcPoly0.x + normals0.x * delta),
                            y = round(srcPoly0.y + normals0.y * delta)))
                } else {
                    k = 1
                    sinA = 0.0
                    if (node.endtype == EndType.OpenSquare)
                        doSquare(0, 1)
                    else
                        doRound(0, 1)
                }
                destPolys.add(destPoly)
            }
        }
    }

    fun execute(solution: Paths, delta: Double) {
        solution.clear()
        fixOrientations()
        doOffset(delta)
        //now clean up 'corners' ...
        val clpr = Clipper()
        clpr.addPaths(destPolys, PolyType.Subject, true)
        if (delta > 0) {
            clpr.execute(ClipType.Union, solution, PolyFillType.Positive, PolyFillType.Positive)
        } else {
            val r = destPolys.bound
            val outer = pathOf(
                    IntPoint(r.left - 10, r.bottom + 10),
                    IntPoint(r.right + 10, r.bottom + 10),
                    IntPoint(r.right + 10, r.top - 10),
                    IntPoint(r.left - 10, r.top - 10)
            )
            clpr.addPath(outer, PolyType.Subject, true)
            clpr.reverseSolution = true
            clpr.execute(ClipType.Union, solution, PolyFillType.Negative, PolyFillType.Negative)
            if (solution.isNotEmpty()) solution.removeAt(0)
        }
    }

    fun execute(solution: PolyTree, delta: Double) {
        solution.clear()
        fixOrientations()
        doOffset(delta)
        //now clean up 'corners' ...
        val clpr = Clipper()
        clpr.addPaths(destPolys, PolyType.Subject, true)
        if (delta > 0) {
            clpr.execute(ClipType.Union, solution, PolyFillType.Positive, PolyFillType.Positive)
        } else {
            val r = destPolys.bound
            val outer = pathOf(
                    IntPoint(r.left - 10, r.bottom + 10),
                    IntPoint(r.right + 10, r.bottom + 10),
                    IntPoint(r.right + 10, r.top - 10),
                    IntPoint(r.left - 10, r.top - 10)
            )
            clpr.addPath(outer, PolyType.Subject, true)
            clpr.reverseSolution = true
            clpr.execute(ClipType.Union, solution, PolyFillType.Negative, PolyFillType.Negative)
            //remove the outer PolyNode rectangle ...
            if (solution.childCount == 1) {
                solution.childs[0].let { outerNode ->
                    if (outerNode.childCount > 0) {
                        solution.childs.ensureCapacity(outerNode.childCount)
                        solution.childs[0] = outerNode.childs[0].apply {
                            parent = solution
                        }
                        for (i in 1 until outerNode.childCount)
                            solution.addChild(outerNode.childs[i])
                    } else {
                        solution.clear()
                    }
                }
            } else {
                solution.clear()
            }
        }
    }

    fun offsetPoint(j: Int, kRef: Ref<Int>, jointype: JoinType) {
        //cross product ...
        val k = kRef.get()
        val normalsK = normals[k]
        val normalsJ = normals[j]
        val srcPolyJ = srcPoly[j]
        sinA = (normalsK.x * normalsJ.y - normalsJ.x * normalsK.y)
        if (Math.abs(sinA * delta) < 1.0) {
            //dot product ...
            val cosA = (normalsK.x * normalsJ.x + normalsJ.y * normalsK.y)
            if (cosA > 0.0)
            // angle ==> 0 degrees
            {
                destPoly.add(IntPoint(
                        x = round(srcPolyJ.x + normalsK.x * delta),
                        y = round(srcPolyJ.y + normalsK.y * delta)))
                return
            }
            //else angle ==> 180 degrees
        } else if (sinA > 1.0)
            sinA = 1.0
        else if (sinA < -1.0) sinA = -1.0
        if (sinA * delta < 0) {
            destPoly.add(IntPoint(
                    x = round(srcPolyJ.x + normalsK.x * delta),
                    y = round(srcPolyJ.y + normalsK.y * delta)))
            destPoly.add(srcPolyJ)
            destPoly.add(IntPoint(
                    x = round(srcPolyJ.x + normalsJ.x * delta),
                    y = round(srcPolyJ.y + normalsJ.y * delta)))
        } else
            when (jointype) {
                JoinType.Miter -> {
                    val r = 1 + normalsJ.x * normalsK.x + normalsJ.y * normalsK.y
                    if (r >= miterLim) doMiter(j, k, r) else doSquare(j, k)
                }
                JoinType.Square -> doSquare(j, k)
                JoinType.Round -> doRound(j, k)
            }
        kRef.set(j)
    }

    internal fun doSquare(j: Int, k: Int) {
        val normalsK = normals[k]
        val normalsJ = normals[j]
        val srcPolyJ = srcPoly[j]
        val dx = Math.tan(Math.atan2(sinA, normalsK.x * normalsJ.x + normalsK.y * normalsJ.y) / 4.0)
        destPoly.add(IntPoint(
                x = round(srcPolyJ.x + delta * (normalsK.x - normalsK.y * dx)),
                y = round(srcPolyJ.y + delta * (normalsK.y + normalsK.x * dx))))
        destPoly.add(IntPoint(
                x = round(srcPolyJ.x + delta * (normalsJ.x + normalsJ.y * dx)),
                y = round(srcPolyJ.y + delta * (normalsJ.y - normalsJ.x * dx))))
    }

    internal fun doMiter(j: Int, k: Int, r: Double) {
        val normalsK = normals[k]
        val normalsJ = normals[j]
        val srcPolyJ = srcPoly[j]
        val q = delta / r
        destPoly.add(IntPoint(
                x = round(srcPolyJ.x + (normalsK.x + normalsJ.x) * q),
                y = round(srcPolyJ.y + (normalsK.y + normalsJ.y) * q)))
    }

    internal fun doRound(j: Int, k: Int) {
        val normalsK = normals[k]
        val normalsJ = normals[j]
        val srcPolyJ = srcPoly[j]
        val a = Math.atan2(sinA, normalsK.x * normalsJ.x + normalsK.y * normalsJ.y)
        val steps = Math.max(round(stepsPerRad * Math.abs(a)), 1)

        var x = normalsK.x
        var y = normalsK.y
        var x2: Double
        for (i in 0..steps - 1) {
            destPoly.add(IntPoint(
                    x = round(srcPolyJ.x + x * delta),
                    y = round(srcPolyJ.y + y * delta)))
            x2 = x
            x = x * cos - sin * y
            y = x2 * sin + y * cos
        }
        destPoly.add(IntPoint(
                x = round(srcPolyJ.x + normalsJ.x * delta),
                y = round(srcPolyJ.y + normalsJ.y * delta)))
    }
}

class ClipperException(description: String) : Exception(description)


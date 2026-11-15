package com.abonoura.aura

data class HttpByteRange(val start:Long,val end:Long,val partial:Boolean) {val length:Long get()=(end-start+1).coerceAtLeast(0)}
object HttpRanges {
    private val pattern=Regex("bytes=(\\d*)-(\\d*)",RegexOption.IGNORE_CASE)
    fun initialOffset(header:String?):Long=header?.trim()?.let{pattern.matchEntire(it)}?.groupValues?.get(1)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
    fun resolve(header:String?,size:Long):HttpByteRange? {
        if(size<0)return null
        if(header==null)return HttpByteRange(0,size-1,false)
        if(size==0L)return null
        val match=pattern.matchEntire(header.trim()) ?: return null
        val first=match.groupValues[1];val last=match.groupValues[2]
        if(first.isEmpty()) {
            val suffix=last.toLongOrNull()?.takeIf{it>0} ?: return null
            return HttpByteRange((size-suffix.coerceAtMost(size)).coerceAtLeast(0),size-1,true)
        }
        val start=first.toLongOrNull()?.takeIf{it<size} ?: return null
        val end=if(last.isEmpty())size-1 else last.toLongOrNull()?.coerceAtMost(size-1) ?: return null
        if(end<start)return null
        return HttpByteRange(start,end,true)
    }
}

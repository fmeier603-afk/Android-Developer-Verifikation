package com.example.runtime

import java.io.ByteArrayInputStream
import java.io.DataInputStream

sealed interface CpEntry {
    data class Utf8(val value: String) : CpEntry
    data class IntVal(val value: Int) : CpEntry
    data class FloatVal(val value: Float) : CpEntry
    data class LongVal(val value: Long) : CpEntry
    data class DoubleVal(val value: Double) : CpEntry
    data class ClassRef(val nameIndex: Int) : CpEntry
    data class StringRef(val stringIndex: Int) : CpEntry
    data class MemberRef(val tag: Int, val classIndex: Int, val nameAndTypeIndex: Int) : CpEntry
    data class NameAndType(val nameIndex: Int, val descriptorIndex: Int) : CpEntry
    data class MethodHandle(val refKind: Int, val refIndex: Int) : CpEntry
    data class MethodType(val descriptorIndex: Int) : CpEntry
    data class InvokeDynamic(val bootstrapMethodAttrIndex: Int, val nameAndTypeIndex: Int) : CpEntry
    data object EmptySlot : CpEntry
}

data class BootstrapMethodEntry(
    val methodHandleIndex: Int,
    val argumentIndices: List<Int>
)

data class ParsedField(
    val accessFlags: Int,
    val name: String,
    val descriptor: String
)

data class ParsedCode(
    val maxStack: Int,
    val maxLocals: Int,
    val bytecode: ByteArray
)

data class ParsedMethod(
    val accessFlags: Int,
    val name: String,
    val descriptor: String,
    val code: ParsedCode?
)

data class ParsedClassFile(
    val thisClass: String,
    val superClass: String,
    val interfaces: List<String>,
    val constantPool: List<CpEntry>,
    val fields: List<ParsedField>,
    val methods: List<ParsedMethod>,
    val bootstrapMethods: List<BootstrapMethodEntry>
) {
    fun utf8(index: Int): String = (constantPool.getOrNull(index) as? CpEntry.Utf8)?.value.orEmpty()

    fun className(classIndex: Int): String {
        val ref = constantPool.getOrNull(classIndex) as? CpEntry.ClassRef ?: return ""
        return utf8(ref.nameIndex)
    }

    fun memberInfo(memberIndex: Int): Triple<String, String, String> {
        val ref = constantPool.getOrNull(memberIndex) as? CpEntry.MemberRef ?: return Triple("", "", "")
        val owner = className(ref.classIndex)
        val nat = constantPool.getOrNull(ref.nameAndTypeIndex) as? CpEntry.NameAndType
            ?: return Triple(owner, "", "")
        return Triple(owner, utf8(nat.nameIndex), utf8(nat.descriptorIndex))
    }

    fun findMethod(name: String, descriptor: String): ParsedMethod? =
        methods.firstOrNull { it.name == name && it.descriptor == descriptor }
}

object JvmClassFileParser {
    fun parse(bytes: ByteArray): ParsedClassFile {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val magic = dis.readInt()
        require(magic == 0xCAFEBABE.toInt()) { "Ungültige .class Magic-Signatur" }
        dis.readUnsignedShort() // minor
        dis.readUnsignedShort() // major

        val cpCount = dis.readUnsignedShort()
        val cp = MutableList<CpEntry>(cpCount) { CpEntry.EmptySlot }
        var i = 1
        while (i < cpCount) {
            val tag = dis.readUnsignedByte()
            when (tag) {
                1 -> cp[i] = CpEntry.Utf8(dis.readUTF())
                3 -> cp[i] = CpEntry.IntVal(dis.readInt())
                4 -> cp[i] = CpEntry.FloatVal(dis.readFloat())
                5 -> {
                    cp[i] = CpEntry.LongVal(dis.readLong())
                    i++ // 8-byte constant occupies 2 slots
                }
                6 -> {
                    cp[i] = CpEntry.DoubleVal(dis.readDouble())
                    i++ // 8-byte constant occupies 2 slots
                }
                7 -> cp[i] = CpEntry.ClassRef(dis.readUnsignedShort())
                8 -> cp[i] = CpEntry.StringRef(dis.readUnsignedShort())
                9, 10, 11 -> cp[i] = CpEntry.MemberRef(tag, dis.readUnsignedShort(), dis.readUnsignedShort())
                12 -> cp[i] = CpEntry.NameAndType(dis.readUnsignedShort(), dis.readUnsignedShort())
                15 -> cp[i] = CpEntry.MethodHandle(dis.readUnsignedByte(), dis.readUnsignedShort())
                16 -> cp[i] = CpEntry.MethodType(dis.readUnsignedShort())
                17, 18 -> cp[i] = CpEntry.InvokeDynamic(dis.readUnsignedShort(), dis.readUnsignedShort())
                19, 20 -> dis.readUnsignedShort() // Module / Package
                else -> error("Unbekannter Constant-Pool-Tag: $tag")
            }
            i++
        }

        dis.readUnsignedShort() // access_flags
        val thisClassIdx = dis.readUnsignedShort()
        val superClassIdx = dis.readUnsignedShort()

        val ifCount = dis.readUnsignedShort()
        val interfaces = (0 until ifCount).map {
            val idx = dis.readUnsignedShort()
            val ref = cp.getOrNull(idx) as? CpEntry.ClassRef
            (ref?.let { cp.getOrNull(it.nameIndex) } as? CpEntry.Utf8)?.value.orEmpty()
        }

        fun readUtf8(idx: Int): String = (cp.getOrNull(idx) as? CpEntry.Utf8)?.value.orEmpty()
        fun readClassName(idx: Int): String {
            val ref = cp.getOrNull(idx) as? CpEntry.ClassRef ?: return ""
            return readUtf8(ref.nameIndex)
        }

        val fieldsCount = dis.readUnsignedShort()
        val fields = mutableListOf<ParsedField>()
        repeat(fieldsCount) {
            val flags = dis.readUnsignedShort()
            val name = readUtf8(dis.readUnsignedShort())
            val desc = readUtf8(dis.readUnsignedShort())
            val attrCount = dis.readUnsignedShort()
            repeat(attrCount) {
                dis.readUnsignedShort()
                val len = dis.readInt()
                skipFully(dis, len)
            }
            fields.add(ParsedField(flags, name, desc))
        }

        val methodsCount = dis.readUnsignedShort()
        val methods = mutableListOf<ParsedMethod>()
        repeat(methodsCount) {
            val flags = dis.readUnsignedShort()
            val name = readUtf8(dis.readUnsignedShort())
            val desc = readUtf8(dis.readUnsignedShort())
            val attrCount = dis.readUnsignedShort()
            var codeAttr: ParsedCode? = null
            repeat(attrCount) {
                val attrName = readUtf8(dis.readUnsignedShort())
                val attrLen = dis.readInt()
                if (attrName == "Code") {
                    val maxStack = dis.readUnsignedShort()
                    val maxLocals = dis.readUnsignedShort()
                    val codeLen = dis.readInt()
                    val bytecode = ByteArray(codeLen)
                    dis.readFully(bytecode)
                    val exTableLen = dis.readUnsignedShort()
                    skipFully(dis, exTableLen * 8)
                    val subAttrCount = dis.readUnsignedShort()
                    repeat(subAttrCount) {
                        dis.readUnsignedShort()
                        val subLen = dis.readInt()
                        skipFully(dis, subLen)
                    }
                    codeAttr = ParsedCode(maxStack, maxLocals, bytecode)
                } else {
                    skipFully(dis, attrLen)
                }
            }
            methods.add(ParsedMethod(flags, name, desc, codeAttr))
        }

        val classAttrCount = dis.readUnsignedShort()
        val bootstrapMethods = mutableListOf<BootstrapMethodEntry>()
        repeat(classAttrCount) {
            val attrName = readUtf8(dis.readUnsignedShort())
            val attrLen = dis.readInt()
            if (attrName == "BootstrapMethods") {
                val num = dis.readUnsignedShort()
                repeat(num) {
                    val mhIdx = dis.readUnsignedShort()
                    val numArgs = dis.readUnsignedShort()
                    val args = (0 until numArgs).map { dis.readUnsignedShort() }
                    bootstrapMethods.add(BootstrapMethodEntry(mhIdx, args))
                }
            } else {
                skipFully(dis, attrLen)
            }
        }

        return ParsedClassFile(
            thisClass = readClassName(thisClassIdx),
            superClass = if (superClassIdx == 0) "" else readClassName(superClassIdx),
            interfaces = interfaces,
            constantPool = cp,
            fields = fields,
            methods = methods,
            bootstrapMethods = bootstrapMethods
        )
    }

    private fun skipFully(dis: DataInputStream, bytes: Int) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = dis.skipBytes(remaining)
            if (skipped <= 0) {
                dis.readByte()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}

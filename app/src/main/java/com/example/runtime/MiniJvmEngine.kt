package com.example.runtime

import com.example.model.CanvasDrawCommand
import com.example.model.JarDialogState
import com.example.model.LogLevel
import com.example.model.UiNodeModel
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class JvmObject(
    val className: String,
    val fields: MutableMap<String, Any?> = mutableMapOf(),
    var nativeState: Any? = null
)

private data class LambdaBinding(
    val interfaceMethodName: String,
    val targetOwner: String,
    val targetMethodName: String,
    val targetDescriptor: String,
    val capturedArgs: List<Any?>
)

class MiniJvmEngine(
    private val jarFile: File,
    private val grantedPermissions: List<String>,
    private val sandboxDir: File,
    private val onUiTree: (UiNodeModel) -> Unit,
    private val onToast: (String) -> Unit,
    private val onDialog: (JarDialogState) -> Unit,
    private val onLog: (LogLevel, String) -> Unit
) {
    private val classes = mutableMapOf<String, ParsedClassFile>()
    private val staticFields = mutableMapOf<String, MutableMap<String, Any?>>()
    private val nodeIdGen = AtomicInteger(1)

    private var clickHandlers = mutableMapOf<String, JvmObject>()
    private var textHandlers = mutableMapOf<String, JvmObject>()
    private var itemHandlers = mutableMapOf<String, JvmObject>()
    private var canvasRenderers = mutableMapOf<String, Pair<JvmObject, JvmObject>>()

    private var appInstance: JvmObject? = null
    private var uiContextObj: JvmObject? = null
    private var totalInstructions = 0L

    fun getInstructionCount(): Long = totalInstructions

    fun start(mainClassDotName: String) {
        loadAllClassesFromJar()
        val internalMain = mainClassDotName.replace('.', '/')
        SandboxPolicy.validateClassAccess(internalMain, grantedPermissions)

        val mainCls = loadClass(internalMain)
            ?: error("Hauptklasse nicht im JAR gefunden: $mainClassDotName")

        onLog(LogLevel.INFO, "Sandbox initialisiert: ${sandboxDir.absolutePath}")
        onLog(LogLevel.INFO, "Lade Hauptklasse: $mainClassDotName (${classes.size} Klassen im JAR)")

        val ctx = JvmObject("de/jarrunner/api/UiContext")
        uiContextObj = ctx

        val runnerMethod = findMethodInHierarchy(mainCls, "onStart", "(Lde/jarrunner/api/UiContext;)V")
        if (runnerMethod != null) {
            val instance = instantiateClass(internalMain)
            appInstance = instance
            invokeParsedMethod(runnerMethod.first, runnerMethod.second, listOf(instance, ctx))
        } else {
            val mainMethod = mainCls.findMethod("main", "([Ljava/lang/String;)V")
                ?: error("Weder RunnerApp.onStart(UiContext) noch main(String[]) gefunden.")
            invokeParsedMethod(mainCls, mainMethod, listOf(arrayOf<String>()))
        }
    }

    fun dispatchClick(nodeId: String) {
        val listener = clickHandlers[nodeId] ?: return
        invokeCallback(listener, "onClick", "()V", emptyList())
    }

    fun dispatchTextChanged(nodeId: String, newText: String) {
        val listener = textHandlers[nodeId] ?: return
        invokeCallback(listener, "onTextChanged", "(Ljava/lang/String;)V", listOf(newText))
    }

    fun dispatchItemClick(nodeId: String, index: Int, item: String) {
        val listener = itemHandlers[nodeId] ?: return
        invokeCallback(listener, "onItemClick", "(ILjava/lang/String;)V", listOf(index, item))
    }

    fun renderCanvasFrame(nodeId: String, width: Int, height: Int, frameTick: Long): List<CanvasDrawCommand> {
        val pair = canvasRenderers[nodeId] ?: return emptyList()
        val canvasObj = pair.first
        val rendererObj = pair.second
        val cmds = (canvasObj.nativeState as? MutableCanvasState)?.commands ?: return emptyList()
        cmds.clear()
        invokeCallback(
            rendererObj,
            "onDraw",
            "(Lde/jarrunner/api/UiCanvas;IIJ)V",
            listOf(canvasObj, width, height, frameTick)
        )
        return cmds.toList()
    }

    private fun invokeCallback(target: JvmObject, methodName: String, descriptor: String, args: List<Any?>) {
        val lambda = target.nativeState as? LambdaBinding
        if (lambda != null) {
            val ownerCls = loadClass(lambda.targetOwner) ?: return
            val m = ownerCls.findMethod(lambda.targetMethodName, lambda.targetDescriptor) ?: return
            invokeParsedMethod(ownerCls, m, lambda.capturedArgs + args)
            return
        }
        val cls = loadClass(target.className) ?: return
        val resolved = findMethodInHierarchy(cls, methodName, descriptor) ?: return
        invokeParsedMethod(resolved.first, resolved.second, listOf(target) + args)
    }

    private fun loadAllClassesFromJar() {
        ZipFile(jarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.isDirectory && entry.name.endsWith(".class")) {
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val parsed = JvmClassFileParser.parse(bytes)
                    classes[parsed.thisClass] = parsed
                }
            }
        }
    }

    private fun loadClass(internalName: String): ParsedClassFile? {
        SandboxPolicy.validateClassAccess(internalName, grantedPermissions)
        val cls = classes[internalName]
        if (cls != null && !staticFields.containsKey(internalName)) {
            staticFields[internalName] = mutableMapOf()
            cls.findMethod("<clinit>", "()V")?.let { clinit ->
                invokeParsedMethod(cls, clinit, emptyList())
            }
        }
        return cls
    }

    private fun instantiateClass(internalName: String): JvmObject {
        val obj = JvmObject(internalName)
        when (internalName) {
            "de/jarrunner/api/UiColumn" -> obj.nativeState = MutableColumnState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiRow" -> obj.nativeState = MutableRowState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiText" -> obj.nativeState = MutableTextState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiButton" -> obj.nativeState = MutableButtonState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiTextField" -> obj.nativeState = MutableTextFieldState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiList" -> obj.nativeState = MutableListState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiCanvas" -> obj.nativeState = MutableCanvasState(nodeIdGen.getAndIncrement())
            "java/util/ArrayList" -> obj.nativeState = mutableListOf<Any?>()
            "java/lang/StringBuilder" -> obj.nativeState = StringBuilder()
        }
        val cls = loadClass(internalName)
        if (cls != null) {
            cls.findMethod("<init>", "()V")?.let { init ->
                invokeParsedMethod(cls, init, listOf(obj))
            }
        }
        return obj
    }

    private fun findMethodInHierarchy(
        start: ParsedClassFile,
        name: String,
        descriptor: String
    ): Pair<ParsedClassFile, ParsedMethod>? {
        var current: ParsedClassFile? = start
        while (current != null) {
            val found = current.findMethod(name, descriptor)
            if (found != null) return current to found
            current = if (current.superClass.isNotEmpty()) loadClass(current.superClass) else null
        }
        return null
    }

    private fun invokeParsedMethod(cls: ParsedClassFile, method: ParsedMethod, args: List<Any?>): Any? {
        val code = method.code ?: return null
        val locals = arrayOfNulls<Any?>(max(code.maxLocals, args.size * 2 + 4))
        val isStatic = (method.accessFlags and 0x0008) != 0
        var slot = 0
        var argIdx = 0
        if (!isStatic && args.isNotEmpty()) {
            locals[slot++] = args[argIdx++]
        }
        val paramTypes = parseParamDescriptors(method.descriptor)
        for (p in paramTypes) {
            val v = args.getOrNull(argIdx++)
            locals[slot] = v
            slot += if (p == "J" || p == "D") 2 else 1
        }

        val stack = ArrayDeque<Any?>()
        val bc = code.bytecode
        var pc = 0
        var stepBudget = 120_000

        while (pc < bc.size) {
            if (--stepBudget <= 0) {
                throw SandboxSecurityException("Ausführungslimit überschritten (Endlosschleife abgebrochen).")
            }
            totalInstructions++
            val op = bc[pc].toInt() and 0xFF
            when (op) {
                0x00 -> pc++ // nop
                0x01 -> { stack.addLast(null); pc++ } // aconst_null
                in 0x02..0x08 -> { stack.addLast(op - 0x03); pc++ } // iconst_m1..5
                0x09 -> { stack.addLast(0L); pc++ }
                0x0A -> { stack.addLast(1L); pc++ }
                0x0B -> { stack.addLast(0f); pc++ }
                0x0C -> { stack.addLast(1f); pc++ }
                0x0D -> { stack.addLast(2f); pc++ }
                0x0E -> { stack.addLast(0.0); pc++ }
                0x0F -> { stack.addLast(1.0); pc++ }
                0x10 -> { stack.addLast(bc[pc + 1].toInt()); pc += 2 } // bipush
                0x11 -> { stack.addLast(readShort(bc, pc + 1)); pc += 3 } // sipush
                0x12 -> { stack.addLast(resolveConstant(cls, bc[pc + 1].toInt() and 0xFF)); pc += 2 } // ldc
                0x13, 0x14 -> { stack.addLast(resolveConstant(cls, readUShort(bc, pc + 1))); pc += 3 } // ldc_w / ldc2_w
                0x15, 0x16, 0x17, 0x18, 0x19 -> {
                    stack.addLast(locals[bc[pc + 1].toInt() and 0xFF])
                    pc += 2
                }
                in 0x1A..0x1D -> { stack.addLast(locals[op - 0x1A]); pc++ } // iload_0..3
                in 0x1E..0x21 -> { stack.addLast(locals[op - 0x1E]); pc++ } // lload_0..3
                in 0x22..0x25 -> { stack.addLast(locals[op - 0x22]); pc++ } // fload_0..3
                in 0x26..0x29 -> { stack.addLast(locals[op - 0x26]); pc++ } // dload_0..3
                in 0x2A..0x2D -> { stack.addLast(locals[op - 0x2A]); pc++ } // aload_0..3
                0x36, 0x37, 0x38, 0x39, 0x3A -> {
                    locals[bc[pc + 1].toInt() and 0xFF] = stack.removeLast()
                    pc += 2
                }
                in 0x3B..0x3E -> { locals[op - 0x3B] = stack.removeLast(); pc++ } // istore_0..3
                in 0x3F..0x42 -> { locals[op - 0x3F] = stack.removeLast(); pc++ } // lstore_0..3
                in 0x43..0x46 -> { locals[op - 0x43] = stack.removeLast(); pc++ } // fstore_0..3
                in 0x47..0x4A -> { locals[op - 0x47] = stack.removeLast(); pc++ } // dstore_0..3
                in 0x4B..0x4E -> { locals[op - 0x4B] = stack.removeLast(); pc++ } // astore_0..3
                0x57 -> { stack.removeLast(); pc++ } // pop
                0x58 -> {
                    val v = stack.removeLast()
                    if (v !is Long && v !is Double && stack.isNotEmpty()) stack.removeLast()
                    pc++
                }
                0x59 -> { stack.addLast(stack.last()); pc++ } // dup
                0x60 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); stack.addLast(a + b); pc++ }
                0x61 -> { val b = asLong(stack.removeLast()); val a = asLong(stack.removeLast()); stack.addLast(a + b); pc++ }
                0x62 -> { val b = asFloat(stack.removeLast()); val a = asFloat(stack.removeLast()); stack.addLast(a + b); pc++ }
                0x63 -> { val b = asDouble(stack.removeLast()); val a = asDouble(stack.removeLast()); stack.addLast(a + b); pc++ }
                0x64 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); stack.addLast(a - b); pc++ }
                0x65 -> { val b = asLong(stack.removeLast()); val a = asLong(stack.removeLast()); stack.addLast(a - b); pc++ }
                0x66 -> { val b = asFloat(stack.removeLast()); val a = asFloat(stack.removeLast()); stack.addLast(a - b); pc++ }
                0x67 -> { val b = asDouble(stack.removeLast()); val a = asDouble(stack.removeLast()); stack.addLast(a - b); pc++ }
                0x68 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); stack.addLast(a * b); pc++ }
                0x69 -> { val b = asLong(stack.removeLast()); val a = asLong(stack.removeLast()); stack.addLast(a * b); pc++ }
                0x6A -> { val b = asFloat(stack.removeLast()); val a = asFloat(stack.removeLast()); stack.addLast(a * b); pc++ }
                0x6B -> { val b = asDouble(stack.removeLast()); val a = asDouble(stack.removeLast()); stack.addLast(a * b); pc++ }
                0x6C -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); stack.addLast(a / b); pc++ }
                0x6D -> { val b = asLong(stack.removeLast()); val a = asLong(stack.removeLast()); stack.addLast(a / b); pc++ }
                0x6E -> { val b = asFloat(stack.removeLast()); val a = asFloat(stack.removeLast()); stack.addLast(a / b); pc++ }
                0x6F -> { val b = asDouble(stack.removeLast()); val a = asDouble(stack.removeLast()); stack.addLast(a / b); pc++ }
                0x70 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); stack.addLast(a % b); pc++ }
                0x74 -> { stack.addLast(-asInt(stack.removeLast())); pc++ }
                0x76 -> { stack.addLast(-asFloat(stack.removeLast())); pc++ }
                0x77 -> { stack.addLast(-asDouble(stack.removeLast())); pc++ }
                0x84 -> { // iinc
                    val idx = bc[pc + 1].toInt() and 0xFF
                    val delta = bc[pc + 2].toInt()
                    locals[idx] = asInt(locals[idx]) + delta
                    pc += 3
                }
                0x85 -> { stack.addLast(asInt(stack.removeLast()).toLong()); pc++ } // i2l
                0x86 -> { stack.addLast(asInt(stack.removeLast()).toFloat()); pc++ } // i2f
                0x87 -> { stack.addLast(asInt(stack.removeLast()).toDouble()); pc++ } // i2d
                0x88 -> { stack.addLast(asLong(stack.removeLast()).toInt()); pc++ } // l2i
                0x89 -> { stack.addLast(asLong(stack.removeLast()).toFloat()); pc++ } // l2f
                0x8A -> { stack.addLast(asLong(stack.removeLast()).toDouble()); pc++ } // l2d
                0x8B -> { stack.addLast(asFloat(stack.removeLast()).toInt()); pc++ } // f2i
                0x8C -> { stack.addLast(asFloat(stack.removeLast()).toLong()); pc++ } // f2l
                0x8D -> { stack.addLast(asFloat(stack.removeLast()).toDouble()); pc++ } // f2d
                0x8E -> { stack.addLast(asDouble(stack.removeLast()).toInt()); pc++ } // d2i
                0x8F -> { stack.addLast(asDouble(stack.removeLast()).toLong()); pc++ } // d2l
                0x90 -> { stack.addLast(asDouble(stack.removeLast()).toFloat()); pc++ } // d2f
                0x94 -> { val b = asLong(stack.removeLast()); val a = asLong(stack.removeLast()); stack.addLast(a.compareTo(b)); pc++ }
                0x95, 0x96 -> { val b = asFloat(stack.removeLast()); val a = asFloat(stack.removeLast()); stack.addLast(a.compareTo(b)); pc++ }
                0x97, 0x98 -> { val b = asDouble(stack.removeLast()); val a = asDouble(stack.removeLast()); stack.addLast(a.compareTo(b)); pc++ }
                0x99 -> pc += if (asInt(stack.removeLast()) == 0) readShort(bc, pc + 1) else 3
                0x9A -> pc += if (asInt(stack.removeLast()) != 0) readShort(bc, pc + 1) else 3
                0x9B -> pc += if (asInt(stack.removeLast()) < 0) readShort(bc, pc + 1) else 3
                0x9C -> pc += if (asInt(stack.removeLast()) >= 0) readShort(bc, pc + 1) else 3
                0x9D -> pc += if (asInt(stack.removeLast()) > 0) readShort(bc, pc + 1) else 3
                0x9E -> pc += if (asInt(stack.removeLast()) <= 0) readShort(bc, pc + 1) else 3
                0x9F -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); pc += if (a == b) readShort(bc, pc + 1) else 3 }
                0xA0 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); pc += if (a != b) readShort(bc, pc + 1) else 3 }
                0xA1 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); pc += if (a < b) readShort(bc, pc + 1) else 3 }
                0xA2 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); pc += if (a >= b) readShort(bc, pc + 1) else 3 }
                0xA3 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); pc += if (a > b) readShort(bc, pc + 1) else 3 }
                0xA4 -> { val b = asInt(stack.removeLast()); val a = asInt(stack.removeLast()); pc += if (a <= b) readShort(bc, pc + 1) else 3 }
                0xA5 -> { val b = stack.removeLast(); val a = stack.removeLast(); pc += if (a === b) readShort(bc, pc + 1) else 3 }
                0xA6 -> { val b = stack.removeLast(); val a = stack.removeLast(); pc += if (a !== b) readShort(bc, pc + 1) else 3 }
                0xA7 -> pc += readShort(bc, pc + 1) // goto
                0xC6 -> pc += if (stack.removeLast() == null) readShort(bc, pc + 1) else 3
                0xC7 -> pc += if (stack.removeLast() != null) readShort(bc, pc + 1) else 3
                0xAC, 0xAD, 0xAE, 0xAF, 0xB0 -> return stack.removeLast()
                0xB1 -> return null
                0xB2 -> { // getstatic
                    val (owner, name, _) = cls.memberInfo(readUShort(bc, pc + 1))
                    SandboxPolicy.validateClassAccess(owner, grantedPermissions)
                    if (owner == "java/lang/System" && name == "out") {
                        stack.addLast(JvmObject("java/io/PrintStream"))
                    } else {
                        stack.addLast(staticFields[owner]?.get(name))
                    }
                    pc += 3
                }
                0xB3 -> { // putstatic
                    val (owner, name, _) = cls.memberInfo(readUShort(bc, pc + 1))
                    staticFields.getOrPut(owner) { mutableMapOf() }[name] = stack.removeLast()
                    pc += 3
                }
                0xB4 -> { // getfield
                    val (_, name, _) = cls.memberInfo(readUShort(bc, pc + 1))
                    val obj = stack.removeLast() as? JvmObject
                    stack.addLast(obj?.fields?.get(name))
                    pc += 3
                }
                0xB5 -> { // putfield
                    val (_, name, _) = cls.memberInfo(readUShort(bc, pc + 1))
                    val value = stack.removeLast()
                    val obj = stack.removeLast() as? JvmObject
                    obj?.fields?.put(name, value)
                    pc += 3
                }
                0xB6, 0xB7, 0xB8, 0xB9 -> { // invokevirtual, invokespecial, invokestatic, invokeinterface
                    val idx = readUShort(bc, pc + 1)
                    val (owner, name, desc) = cls.memberInfo(idx)
                    SandboxPolicy.validateClassAccess(owner, grantedPermissions)
                    SandboxPolicy.validateMethodCall(owner, name)

                    val isStaticCall = op == 0xB8
                    val paramCount = parseParamDescriptors(desc).size
                    val poppedArgs = ArrayList<Any?>(paramCount)
                    repeat(paramCount) { poppedArgs.add(0, stack.removeLast()) }
                    val receiver = if (isStaticCall) null else stack.removeLast()

                    val result = dispatchInvocation(owner, name, desc, receiver, poppedArgs, isSpecial = op == 0xB7)
                    if (!desc.endsWith(")V")) {
                        stack.addLast(result)
                    }
                    pc += if (op == 0xB9) 5 else 3
                }
                0xBA -> { // invokedynamic
                    val indyIdx = readUShort(bc, pc + 1)
                    val indy = cls.constantPool[indyIdx] as CpEntry.InvokeDynamic
                    val nat = cls.constantPool[indy.nameAndTypeIndex] as CpEntry.NameAndType
                    val methodName = cls.utf8(nat.nameIndex)
                    val methodDesc = cls.utf8(nat.descriptorIndex)
                    val bsm = cls.bootstrapMethods[indy.bootstrapMethodAttrIndex]
                    val mh = cls.constantPool[bsm.methodHandleIndex] as CpEntry.MethodHandle
                    val (bsmOwner, bsmName, _) = cls.memberInfo(mh.refIndex)

                    val paramCount = parseParamDescriptors(methodDesc).size
                    val callArgs = ArrayList<Any?>(paramCount)
                    repeat(paramCount) { callArgs.add(0, stack.removeLast()) }

                    val result = handleInvokeDynamic(cls, bsmOwner, bsmName, methodName, methodDesc, bsm, callArgs)
                    stack.addLast(result)
                    pc += 5
                }
                0xBB -> { // new
                    val className = cls.className(readUShort(bc, pc + 1))
                    SandboxPolicy.validateClassAccess(className, grantedPermissions)
                    val obj = instantiateRaw(className)
                    stack.addLast(obj)
                    pc += 3
                }
                0xBC -> { // newarray
                    val count = asInt(stack.removeLast())
                    stack.addLast(arrayOfNulls<Any?>(count))
                    pc += 2
                }
                0xBD -> { // anewarray
                    val count = asInt(stack.removeLast())
                    stack.addLast(arrayOfNulls<Any?>(count))
                    pc += 3
                }
                0xBE -> { // arraylength
                    val arr = stack.removeLast() as? Array<*>
                    stack.addLast(arr?.size ?: 0)
                    pc += 1
                }
                0xC0 -> pc += 3 // checkcast
                0xC1 -> { stack.addLast(if (stack.removeLast() != null) 1 else 0); pc += 3 }
                else -> error("Nicht unterstützter Opcode 0x${op.toString(16)} bei pc=$pc in ${cls.thisClass}.$method.name")
            }
        }
        return null
    }

    private fun instantiateRaw(internalName: String): JvmObject {
        val obj = JvmObject(internalName)
        when (internalName) {
            "de/jarrunner/api/UiColumn" -> obj.nativeState = MutableColumnState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiRow" -> obj.nativeState = MutableRowState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiText" -> obj.nativeState = MutableTextState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiButton" -> obj.nativeState = MutableButtonState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiTextField" -> obj.nativeState = MutableTextFieldState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiList" -> obj.nativeState = MutableListState(nodeIdGen.getAndIncrement())
            "de/jarrunner/api/UiCanvas" -> obj.nativeState = MutableCanvasState(nodeIdGen.getAndIncrement())
            "java/util/ArrayList" -> obj.nativeState = mutableListOf<Any?>()
            "java/lang/StringBuilder" -> obj.nativeState = StringBuilder()
        }
        return obj
    }

    private fun handleInvokeDynamic(
        cls: ParsedClassFile,
        bsmOwner: String,
        bsmName: String,
        invokedName: String,
        invokedDesc: String,
        bsm: BootstrapMethodEntry,
        callArgs: List<Any?>
    ): Any? {
        if (bsmOwner == "java/lang/invoke/StringConcatFactory" && bsmName == "makeConcatWithConstants") {
            val recipe = resolveConstant(cls, bsm.argumentIndices.firstOrNull() ?: 0)?.toString().orEmpty()
            val sb = StringBuilder()
            var argIdx = 0
            for (ch in recipe) {
                if (ch == '\u0001') {
                    sb.append(callArgs.getOrNull(argIdx++) ?: "null")
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        if (bsmOwner == "java/lang/invoke/LambdaMetafactory") {
            val mhIdx = bsm.argumentIndices.getOrNull(1) ?: 0
            val mh = cls.constantPool[mhIdx] as CpEntry.MethodHandle
            val (tOwner, tName, tDesc) = cls.memberInfo(mh.refIndex)
            val returnIface = invokedDesc.substringAfter(')').removePrefix("L").removeSuffix(";")
            return JvmObject(
                className = returnIface,
                nativeState = LambdaBinding(invokedName, tOwner, tName, tDesc, callArgs)
            )
        }
        return null
    }

    private fun dispatchInvocation(
        owner: String,
        name: String,
        desc: String,
        receiver: Any?,
        args: List<Any?>,
        isSpecial: Boolean
    ): Any? {
        // 1. Built-in JDK & Runner API handlers
        if (owner == "java/lang/Object" && name == "<init>") return null

        if (owner == "java/io/PrintStream" && (name == "println" || name == "print")) {
            onLog(LogLevel.STDOUT, args.firstOrNull()?.toString() ?: "")
            return null
        }

        if (owner == "java/lang/System") {
            return when (name) {
                "currentTimeMillis" -> System.currentTimeMillis()
                "nanoTime" -> System.nanoTime()
                else -> null
            }
        }

        if (owner == "java/lang/Math") {
            return when (name) {
                "cos" -> cos(asDouble(args[0]))
                "sin" -> sin(asDouble(args[0]))
                "sqrt" -> sqrt(asDouble(args[0]))
                "abs" -> if (desc.startsWith("(D")) abs(asDouble(args[0])) else if (desc.startsWith("(F")) abs(asFloat(args[0])) else abs(asInt(args[0]))
                "min" -> min(asInt(args[0]), asInt(args[1]))
                "max" -> max(asInt(args[0]), asInt(args[1]))
                else -> 0.0
            }
        }

        if (owner == "java/lang/String") {
            val str = receiver as? String ?: ""
            return when (name) {
                "isEmpty" -> if (str.isEmpty()) 1 else 0
                "length" -> str.length
                "equals" -> if (str == args.firstOrNull()) 1 else 0
                "valueOf" -> args.firstOrNull()?.toString() ?: "null"
                else -> str
            }
        }

        if (owner == "java/lang/StringBuilder") {
            val sb = (receiver as? JvmObject)?.nativeState as? StringBuilder ?: StringBuilder()
            return when (name) {
                "<init>" -> {
                    if (args.isNotEmpty()) sb.append(args[0]?.toString() ?: "")
                    null
                }
                "append" -> {
                    sb.append(args.firstOrNull()?.toString() ?: "null")
                    receiver
                }
                "toString" -> sb.toString()
                else -> receiver
            }
        }

        if (owner == "java/util/ArrayList" || owner == "java/util/List") {
            @Suppress("UNCHECKED_CAST")
            val list = (receiver as? JvmObject)?.nativeState as? MutableList<Any?> ?: mutableListOf()
            return when (name) {
                "<init>" -> null
                "add" -> {
                    if (args.size == 2) {
                        list.add(asInt(args[0]), args[1])
                        null
                    } else {
                        list.add(args[0])
                        1
                    }
                }
                "size" -> list.size
                "get" -> list.getOrNull(asInt(args[0]))
                "remove" -> list.removeAt(asInt(args[0]))
                "clear" -> { list.clear(); null }
                else -> null
            }
        }

        // Runner API UI Bridge
        if (owner.startsWith("de/jarrunner/api/")) {
            return handleRunnerApiCall(owner, name, receiver as? JvmObject, args)
        }

        // 2. Loaded JAR bytecode classes
        val targetClassName = if (isSpecial) owner else (receiver as? JvmObject)?.className ?: owner
        val targetCls = loadClass(targetClassName) ?: return null
        val resolved = findMethodInHierarchy(targetCls, name, desc) ?: return null
        val callArgs = if (receiver != null) listOf(receiver) + args else args
        return invokeParsedMethod(resolved.first, resolved.second, callArgs)
    }

    private fun handleRunnerApiCall(owner: String, name: String, obj: JvmObject?, args: List<Any?>): Any? {
        if (owner == "de/jarrunner/api/UiContext") {
            when (name) {
                "setContent" -> {
                    val rootObj = args.firstOrNull() as? JvmObject ?: return null
                    clickHandlers = mutableMapOf()
                    textHandlers = mutableMapOf()
                    itemHandlers = mutableMapOf()
                    canvasRenderers = mutableMapOf()
                    val model = convertToUiModel(rootObj)
                    onUiTree(model)
                }
                "showToast" -> onToast(args.firstOrNull()?.toString().orEmpty())
                "showDialog" -> onDialog(
                    JarDialogState(
                        title = args.getOrNull(0)?.toString().orEmpty(),
                        message = args.getOrNull(1)?.toString().orEmpty(),
                        confirmLabel = args.getOrNull(2)?.toString().orEmpty().ifBlank { "OK" }
                    )
                )
                "log" -> onLog(LogLevel.STDOUT, args.firstOrNull()?.toString().orEmpty())
                "getAppDataDir" -> return sandboxDir.absolutePath
            }
            return null
        }

        when (val state = obj?.nativeState) {
            is MutableColumnState -> when (name) {
                "<init>" -> Unit
                "add" -> (args.firstOrNull() as? JvmObject)?.let { state.children.add(it) }
                "setSpacingDp" -> state.spacingDp = asInt(args.firstOrNull())
            }
            is MutableRowState -> when (name) {
                "<init>" -> Unit
                "add" -> (args.firstOrNull() as? JvmObject)?.let { state.children.add(it) }
                "setSpacingDp" -> state.spacingDp = asInt(args.firstOrNull())
            }
            is MutableTextState -> when (name) {
                "<init>" -> state.text = args.firstOrNull()?.toString().orEmpty()
                "setText" -> state.text = args.firstOrNull()?.toString().orEmpty()
                "setStyle" -> state.style = args.firstOrNull()?.toString().orEmpty()
                "setColorHex" -> state.colorHex = args.firstOrNull()?.toString().orEmpty()
            }
            is MutableButtonState -> when (name) {
                "<init>" -> state.label = args.firstOrNull()?.toString().orEmpty()
                "setLabel" -> state.label = args.firstOrNull()?.toString().orEmpty()
                "setVariant" -> state.variant = args.firstOrNull()?.toString().orEmpty()
                "setOnClick" -> state.onClick = args.firstOrNull() as? JvmObject
            }
            is MutableTextFieldState -> when (name) {
                "<init>" -> state.hint = args.firstOrNull()?.toString().orEmpty()
                "setValue" -> state.value = args.firstOrNull()?.toString().orEmpty()
                "setHint" -> state.hint = args.firstOrNull()?.toString().orEmpty()
                "setOnChange" -> state.onChange = args.firstOrNull() as? JvmObject
            }
            is MutableListState -> when (name) {
                "<init>" -> Unit
                "setItems" -> {
                    @Suppress("UNCHECKED_CAST")
                    val list = (args.firstOrNull() as? JvmObject)?.nativeState as? List<Any?>
                    state.items.clear()
                    list?.forEach { state.items.add(it?.toString().orEmpty()) }
                }
                "addItem" -> state.items.add(args.firstOrNull()?.toString().orEmpty())
                "setOnItemClick" -> state.onItemClick = args.firstOrNull() as? JvmObject
            }
            is MutableCanvasState -> when (name) {
                "<init>" -> Unit
                "setHeightDp" -> state.heightDp = asInt(args.firstOrNull())
                "setBackgroundHex" -> state.bgHex = args.firstOrNull()?.toString().orEmpty()
                "setRenderer" -> state.renderer = args.firstOrNull() as? JvmObject
                "clear" -> state.commands.clear()
                "drawCircle" -> state.commands.add(
                    CanvasDrawCommand.Circle(
                        asFloat(args[0]), asFloat(args[1]), asFloat(args[2]), args[3]?.toString().orEmpty()
                    )
                )
                "drawRect" -> state.commands.add(
                    CanvasDrawCommand.Rect(
                        asFloat(args[0]), asFloat(args[1]), asFloat(args[2]), asFloat(args[3]), args[4]?.toString().orEmpty()
                    )
                )
                "drawLine" -> state.commands.add(
                    CanvasDrawCommand.Line(
                        asFloat(args[0]), asFloat(args[1]), asFloat(args[2]), asFloat(args[3]), asFloat(args[4]), args[5]?.toString().orEmpty()
                    )
                )
                "drawText" -> state.commands.add(
                    CanvasDrawCommand.Text(
                        args[0]?.toString().orEmpty(), asFloat(args[1]), asFloat(args[2]), asFloat(args[3]), args[4]?.toString().orEmpty()
                    )
                )
            }
        }
        return obj
    }

    private fun convertToUiModel(obj: JvmObject): UiNodeModel {
        return when (val state = obj.nativeState) {
            is MutableColumnState -> UiNodeModel.ColumnNode(
                id = "node_${state.id}",
                spacingDp = state.spacingDp,
                children = state.children.map { convertToUiModel(it) }
            )
            is MutableRowState -> UiNodeModel.RowNode(
                id = "node_${state.id}",
                spacingDp = state.spacingDp,
                children = state.children.map { convertToUiModel(it) }
            )
            is MutableTextState -> UiNodeModel.TextNode(
                id = "node_${state.id}",
                text = state.text,
                style = state.style,
                colorHex = state.colorHex
            )
            is MutableButtonState -> {
                val id = "node_${state.id}"
                state.onClick?.let { clickHandlers[id] = it }
                UiNodeModel.ButtonNode(id = id, label = state.label, variant = state.variant)
            }
            is MutableTextFieldState -> {
                val id = "node_${state.id}"
                state.onChange?.let { textHandlers[id] = it }
                UiNodeModel.TextFieldNode(id = id, value = state.value, hint = state.hint)
            }
            is MutableListState -> {
                val id = "node_${state.id}"
                state.onItemClick?.let { itemHandlers[id] = it }
                UiNodeModel.ListNode(id = id, items = state.items.toList())
            }
            is MutableCanvasState -> {
                val id = "node_${state.id}"
                state.renderer?.let { canvasRenderers[id] = obj to it }
                UiNodeModel.CanvasNode(id = id, heightDp = state.heightDp, backgroundHex = state.bgHex)
            }
            else -> UiNodeModel.TextNode(id = "unknown", text = "Node: ${obj.className}")
        }
    }

    private fun resolveConstant(cls: ParsedClassFile, index: Int): Any? {
        return when (val entry = cls.constantPool.getOrNull(index)) {
            is CpEntry.IntVal -> entry.value
            is CpEntry.FloatVal -> entry.value
            is CpEntry.LongVal -> entry.value
            is CpEntry.DoubleVal -> entry.value
            is CpEntry.StringRef -> cls.utf8(entry.stringIndex)
            is CpEntry.Utf8 -> entry.value
            is CpEntry.ClassRef -> cls.utf8(entry.nameIndex)
            else -> null
        }
    }

    private fun parseParamDescriptors(desc: String): List<String> {
        val params = mutableListOf<String>()
        var i = desc.indexOf('(') + 1
        val end = desc.indexOf(')')
        while (i in 1 until end) {
            when (desc[i]) {
                'L' -> {
                    val semi = desc.indexOf(';', i)
                    params.add(desc.substring(i, semi + 1))
                    i = semi + 1
                }
                '[' -> {
                    val start = i
                    while (desc[i] == '[') i++
                    if (desc[i] == 'L') {
                        val semi = desc.indexOf(';', i)
                        params.add(desc.substring(start, semi + 1))
                        i = semi + 1
                    } else {
                        params.add(desc.substring(start, i + 1))
                        i++
                    }
                }
                else -> {
                    params.add(desc[i].toString())
                    i++
                }
            }
        }
        return params
    }

    private fun readShort(bc: ByteArray, idx: Int): Int =
        ((bc[idx].toInt() shl 8) or (bc[idx + 1].toInt() and 0xFF)).toShort().toInt()

    private fun readUShort(bc: ByteArray, idx: Int): Int =
        ((bc[idx].toInt() and 0xFF) shl 8) or (bc[idx + 1].toInt() and 0xFF)

    private fun asInt(v: Any?): Int = (v as? Number)?.toInt() ?: 0
    private fun asLong(v: Any?): Long = (v as? Number)?.toLong() ?: 0L
    private fun asFloat(v: Any?): Float = (v as? Number)?.toFloat() ?: 0f
    private fun asDouble(v: Any?): Double = (v as? Number)?.toDouble() ?: 0.0

    private class MutableColumnState(val id: Int, var spacingDp: Int = 12, val children: MutableList<JvmObject> = mutableListOf())
    private class MutableRowState(val id: Int, var spacingDp: Int = 8, val children: MutableList<JvmObject> = mutableListOf())
    private class MutableTextState(val id: Int, var text: String = "", var style: String = "body", var colorHex: String = "")
    private class MutableButtonState(val id: Int, var label: String = "", var variant: String = "primary", var onClick: JvmObject? = null)
    private class MutableTextFieldState(val id: Int, var value: String = "", var hint: String = "", var onChange: JvmObject? = null)
    private class MutableListState(val id: Int, val items: MutableList<String> = mutableListOf(), var onItemClick: JvmObject? = null)
    private class MutableCanvasState(
        val id: Int,
        var heightDp: Int = 240,
        var bgHex: String = "#090F1D",
        var renderer: JvmObject? = null,
        val commands: MutableList<CanvasDrawCommand> = mutableListOf()
    )
}

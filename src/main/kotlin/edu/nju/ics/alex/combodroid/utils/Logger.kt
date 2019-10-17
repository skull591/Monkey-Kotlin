package edu.nju.ics.alex.combodroid.utils

import org.w3c.dom.Document
import java.io.IOException
import java.lang.Exception


object Logger {

    private const val debug = false
    const val TAG = "[ComboDroid]"

    fun lPrintln (message: Any) { println("$TAG $message")}

    fun wLPrintln (message: Any) { println("$TAG *** WARNING *** $message")}

    fun iLPrintln (message: Any) { println("$TAG *** INFO *** $message")}


//
//    fun varLPrintln (type: String, vararg args: Any) {  println("$TAG $type ${args.joinToString(" ")}") }
//
//    fun dVarLPrintln (type: String, vararg args: Any) {  if (debug) println("$TAG *** DEBUG *** $type ${args.joinToString(" ")}") }
//
//    fun wVarLPrintln (type: String, vararg args: Any) {  println("$TAG *** WARNING *** $type ${args.joinToString(" ")}") }
//
//    fun iVarLPrintln (type: String, vararg args: Any) {  println("$TAG *** INFO *** $type ${args.joinToString(" ")}") }
//
//    fun printXml(document: Document) {
//        try {
//            printXml(System.out, document)
//            println()
//        }catch (e: Exception) {
//            e.printStackTrace()
//        }
//    }

}


fun test() {
    throw IOException("for fun")
}


// for testing purpose only
fun main() {
    //System.out.format("[lala] %s\n", "test")
    //Logger.lPrintln("test")
   // Logger.typePrintln("test","1","2")
//    try {
//        test()
//    } catch (e: Exception) {
//        e.printStackTrace()
//    }
//    println("going here!")

//    val test = arrayListOf(1,2,3)
//    var i = 0
//    println(test[i++])
//    println(i)
//    val t = 1
//    val b = throw Exception()
//    try {
//        val a = if (t == 3) {2} else {throw Exception()}
//    } catch (e: Exception) {
//        e.printStackTrace()
//    }
    val test = arrayListOf<Int?>()
    test.add(null)
    println(test.size)
    println(test[0])
}
package edu.nju.ics.alex.combodroid.utils

import org.w3c.dom.Document
import java.io.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult




object Utils {
    fun printXml(stream: OutputStream, document: Document) {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.transform(DOMSource(document), StreamResult(stream))
    }

    @Throws(IOException::class, InterruptedException::class)
    fun getProcessOutput(cmd: Array<String>): List<String> {
        val processBuilder = ProcessBuilder(*cmd)
        processBuilder.redirectErrorStream(true)
        val process = processBuilder.start()
        return BufferedReader(
            InputStreamReader(process.inputStream)
        ).readLines()
    }

    fun <K,V> addtoMapSet(setMap: MutableMap<K, MutableSet<V>>, key: K, value: V) : Boolean {
        val set = setMap[key] ?: mutableSetOf()
        setMap[key] = set
        return set.add(value)
    }

    fun dump(array: Array<Any>) {
        for (i in array.indices) {
            Logger.iLPrintln("$i ${array[i]}")
        }
    }

    @Throws(Exception::class)
    fun readXml(filename: String): Document {
        val dbf = DocumentBuilderFactory.newInstance()
        val db = dbf.newDocumentBuilder()
        return db.parse(File(filename))
    }

    fun <K, V> putIfAbsent(map: MutableMap<K, V>, key : K, value : V) : Boolean
            = if (map.contains(key)) { false }
            else { map.put(key, value) == null }

    fun <K, K2, V> getFromMapMap(mapMap: Map<K, Map<K2, V>>, key: K, key2: K2) : V?
            = mapMap[key]?.get(key2)

    fun <K, K2, V> addToMapMap(mapMap: MutableMap<K, MutableMap<K2, V>>, key: K, key2: K2, value: V) : V?
            = (mapMap[key] ?: mutableMapOf()).also { mapMap[key] = it }.put(key2,value)

    fun <K, K2, V> addToMapMapIfAbsent(mapMap: MutableMap<K, MutableMap<K2, V>>, key: K, key2: K2, value : V) : Boolean
            = (mapMap[key] ?: (mutableMapOf<K2, V>().also { mapMap[key] = it })).run { putIfAbsent(this, key2, value) }

    fun <K, K2, V> removeFromMapMap(mapMap: MutableMap<K, MutableMap<K2, V>>, key: K, key2: K2) : V?
            = mapMap[key]?.remove(key2)

    fun <K, K2, V> getMapFromMap(mapMap: MutableMap<K, Map<K2, V>>, key: K) : Map<K2, V>
            = mapMap[key] ?: (mutableMapOf<K2, V>().also{ mapMap[key] = it })

    fun <K, V> removeFromMapSet(mapSet: MutableMap<K, MutableSet<V>>, key: K, value : V) : Boolean
            = mapSet[key]?.remove(value) ?: false
}
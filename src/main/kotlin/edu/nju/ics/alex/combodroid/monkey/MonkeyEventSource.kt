package edu.nju.ics.alex.combodroid.monkey

/**
 * event source interface
 * */
interface MonkeyEventSource {
    /**
     * @return the next monkey event from the source
     */
    abstract fun getNextEvent(): MonkeyEvent?

    /**
     * set verbose to allow different level of log
     *
     * @param verbose
     * output mode? 1= verbose, 2=very verbose
     */
    abstract fun setVerbose(verbose: Int)

    /**
     * check whether precondition is satisfied
     *
     * @return false if something fails, e.g. factor failure in random source or
     * file can not open from script source etc
     */
    abstract fun validate(): Boolean
}
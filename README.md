A kotlin-version of Android Monkey

Execution:
(1) Use gradle to generate jar file,
(2) Run deployNRun.sh to push the jar file to the Android device, and
(3) Run runCD.sh "#parameters" to run the Monkey.

Note: 
(1) The default probabilities of events are changed, and
(2) When running with runCD.sh, put all parameters inside "" to ensure that it recieves all parameters

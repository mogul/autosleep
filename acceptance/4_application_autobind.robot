*** Settings ***
Resource        Keywords.robot
Documentation   Test if application autobound is stopped
Force Tags      Service broker
Test Teardown   Run Keywords  Clean

*** Variables ***
${INACTIVITY_IN_S}  30
${INACTIVITY}  PT${INACTIVITY_IN_S}S

*** Test Cases ***

1) Automatically bind application by service instance
    [Documentation]     Check that app is automatically bound by service instance
    Clean
    ${regex} 					Catenate   SEPARATOR=  	^(?:(?!    ${TESTED_APP_NAME}   ).)*$
	Create service instance		${INACTIVITY}    ${regex}
	Sleep               		15
	Check App Binded


2) Service does not bind ignored applications
	[Documentation]		Check that no application is bound by the service instance
	Clean
	Create service instance		${INACTIVITY}
	Sleep						15
	Check No App Binded




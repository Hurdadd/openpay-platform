rootProject.name = "openpay-platform"
include("payment-orchestrator" , "psp-gateway" , "ledger-service")
include("payment-orchestrator:domain")
include("payment-orchestrator:application")
include("payment-orchestrator:adapter")
include(":payment-orchestrator:bootstrap")

include("ledger-service:domain")
include("ledger-service:application")
include("ledger-service:adapter")
include("ledger-service:bootstrap")

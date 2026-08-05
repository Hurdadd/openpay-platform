rootProject.name = "openpay-platform"
include("payment-orchestrator" , "psp-gateway" , "ledger-service")
include("payment-orchestrator:domain")
include("payment-orchestrator:application")
findProject(":payment-orchestrator:application")?.name = "application"
include("payment-orchestrator:adapter")
include(":payment-orchestrator:bootstrap")
findProject(":payment-orchestrator:adapter")?.name = "adapter"

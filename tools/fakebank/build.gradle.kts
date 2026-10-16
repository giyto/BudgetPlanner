// Инструмент разработки «Фейковый банк» (design.md add-balance-reconciliation, решение 9): присылает уведомления
// от имени пакета банка, чтобы пройти автоимпорт и сверку на эмуляторе. Ставится только на эмулятор, см. README.md.
// В сборку BudgetPlanner не входит: модуль :app от него не зависит.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ru.hznik.fakebank"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "bank"
    productFlavors {
        // Пакеты совпадают с SupportedBanks в :app — иначе фильтр автоимпорта уведомление не пропустит.
        create("sber") {
            dimension = "bank"
            applicationId = "ru.sberbankmobile"
            resValue("string", "bank_name", "СберБанк")
            resValue("string", "bank_id", "sber")
        }
        create("tbank") {
            dimension = "bank"
            applicationId = "com.idamob.tinkoff.android"
            resValue("string", "bank_name", "Т-Банк")
            resValue("string", "bank_id", "tbank")
        }
        create("alfa") {
            dimension = "bank"
            applicationId = "ru.alfabank.mobile.android"
            resValue("string", "bank_name", "Альфа-Банк")
            resValue("string", "bank_id", "alfa")
        }
    }

    buildFeatures {
        resValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

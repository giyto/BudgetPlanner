package ru.hznik.budget.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Ключ базы: 32 случайных байта, зашифрованные ключом Android Keystore (design.md add-data-protection, решение 1).
 * Ключ Keystore не привязан к блокировке экрана, поэтому смена PIN или отпечатков его не сбрасывает.
 * Файл лежит в `noBackupFilesDir` и не попадает в бэкап.
 */
class DatabaseKeyStore(
    private val file: File,
    private val alias: String = DEFAULT_ALIAS,
) {
    sealed interface Result {
        class Present(val key: ByteArray) : Result

        /** Файла ключа нет: первая установка или данные приложения стёрты. */
        data object Missing : Result

        /** Ключ точно потерян: ключа Keystore нет, он не подходит или файл испорчен. */
        data object Unreadable : Result

        /** Временный сбой (ошибка Keystore на части прошивок, ввод-вывод): ключ, возможно, цел, стоит повторить. */
        data object Failed : Result
    }

    fun read(): Result {
        if (!file.exists()) return Result.Missing
        // Потерянным ключ считается только в однозначных случаях: после этого пользователю предлагают удалить данные.
        return try {
            DataInputStream(AtomicFile(file).openRead()).use { input ->
                if (input.readInt() != FORMAT_VERSION) return Result.Unreadable
                val ivSize = input.readInt()
                if (ivSize !in 1..MAX_PART) return Result.Unreadable
                val iv = ByteArray(ivSize).also(input::readFully)
                val encryptedSize = input.readInt()
                if (encryptedSize !in 1..MAX_PART) return Result.Unreadable
                val encrypted = ByteArray(encryptedSize).also(input::readFully)
                val wrapping = keystoreKey() ?: return Result.Unreadable
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, wrapping, GCMParameterSpec(TAG_BITS, iv))
                Result.Present(cipher.doFinal(encrypted))
            }
        } catch (e: EOFException) {
            Result.Unreadable
        } catch (e: AEADBadTagException) {
            Result.Unreadable
        } catch (e: UnrecoverableKeyException) {
            Result.Unreadable
        } catch (e: Exception) {
            // Сообщение исключения не содержит ключа; сам ключ никуда не пишется.
            Result.Failed
        }
    }

    /** Создаёт новый ключ базы и записывает его; прежний ключ Keystore заменяется. */
    fun create(): ByteArray {
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val wrapping = newKeystoreKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrapping)
        val encrypted = cipher.doFinal(key)
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            DataOutputStream(stream).apply {
                writeInt(FORMAT_VERSION)
                writeInt(cipher.iv.size)
                write(cipher.iv)
                writeInt(encrypted.size)
                write(encrypted)
                flush()
            }
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
        return key
    }

    fun delete() {
        AtomicFile(file).delete()
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun keystoreKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun newKeystoreKey(): SecretKey {
        runCatching { keyStore().deleteEntry(alias) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "budget_db_wrap"
        const val FILE_NAME = "db_key.bin"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_BYTES = 32
        private const val FORMAT_VERSION = 1
        private const val MAX_PART = 1024
    }
}

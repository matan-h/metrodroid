/*
 * TransitCurrency.kt
 *
 * Copyright 2018 Google
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package au.id.micolous.metrodroid.transit

import au.id.micolous.metrodroid.multi.*
import au.id.micolous.metrodroid.util.ISO4217
import au.id.micolous.metrodroid.util.NumberUtils
import au.id.micolous.metrodroid.util.Preferences
import au.id.micolous.metrodroid.util.TripObfuscator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.random.Random

internal expect fun formatCurrency(value: Int, divisor: Int, currencyCode: String, isBalance: Boolean): FormattedString

@Serializable
@SerialName("currency")
sealed class TransitCurrencyBase : TransitBalance(), Parcelable {
    abstract fun formatCurrencyString(isBalance: Boolean): FormattedString
    abstract fun obfuscate(): TransitCurrencyBase

    @Suppress("USELESS_CAST")
    override fun equals(other: Any?): Boolean = when (this) {
        is TransitCurrencyResource -> this as TransitCurrencyResource == other
        is TransitCurrency -> this as TransitCurrency == other
    }

    @Suppress("USELESS_CAST")
    override fun hashCode(): Int = when (this) {
        is TransitCurrencyResource -> (this as TransitCurrencyResource).hashCode()
        is TransitCurrency -> (this as TransitCurrency).hashCode()
    }
}

@Serializable
@SerialName("resource")
@Parcelize
data class TransitCurrencyResource (
    @Serializable(with = StringResourceSerializer::class)
    private val mDesc: StringResource)
    : TransitCurrencyBase () {
    override fun formatCurrencyString(isBalance: Boolean) =
        Localizer.localizeFormatted(mDesc)
    override fun obfuscate(): TransitCurrencyResource = this
    override val balance: TransitCurrency
        get() = TransitCurrency.USD(0)
}

@Parcelize
@Serializable
@SerialName("iso")
data class TransitCurrency (
        @SerialName("value")
        val mCurrency: Int,
        /**
         * 3 character currency code (eg: AUD) per ISO 4217.
         */
        @VisibleForTesting
        @SerialName("currencyCode")
        val mCurrencyCode: String,
        /**
         * Value to divide by to get that currency's value in non-fractional parts.
         *
         * If the value passed is in cents, then divide by 100 to get dollars. This is the default.
         *
         * If the currency has no fractional part (eg: IDR, JPY, KRW), then the divisor should be 1,
         */
        @VisibleForTesting
        @SerialName("divisor")
        val mDivisor: Int
): TransitCurrencyBase(), Parcelable {

    override val balance: TransitCurrency
        get() = this

    /**
     * Builds a new [TransitCurrency], used to represent a monetary value on a transit card.
     *
     * The `divisor` parameter will default to:
     *
     * * For `currencyCode: String`: [DEFAULT_DIVISOR] (100).
     *
     * * For `currencyCode: Int`: Looked up dynamically with [ISO4217.getInfoByCode].
     *
     * Constructors taking a numeric ISO 4217 `currencyCode: Int` will accept unknown currency
     * codes, replacing them with [UNKNOWN_CURRENCY_CODE] (XXX).
     *
     * Constructors taking a [ISO4217.CurrencyInfo] parameter are intended for
     * internal (to [TransitCurrency]) use. Do not use them outside of this class.
     *
     * Style note: If the [TransitData] only ever supports a single currency, prefer to use
     * one of the static methods of [TransitCurrency] (eg: [AUD]) to build values,
     * rather than calling this constructor with a constant {@param currencyCode} string.
     *
     * @param currency The amount of currency
     * @param currencyCode An ISO 4217 textual currency code, eg: "AUD".
     */
    constructor(currency: Int, currencyCode: String) : this(currency, currencyCode, DEFAULT_DIVISOR)

    /**
     * @inheritDoc
     *
     * @param currencyCode An ISO 4217 numeric currency code
     */
    constructor(currency: Int, currencyCode: Int) : this(currency, ISO4217.getInfoByCode(currencyCode))

    /**
     * @inheritDoc
     *
     * @param currencyCode An ISO 4217 numeric currency code
     */
    constructor(currency: Int, currencyCode: Int, divisor: Int) : this(currency, ISO4217.getInfoByCode(currencyCode), divisor)

    /**
     * @inheritDoc
     *
     * @param currencyDesc A [ISO4217.CurrencyInfo] instance for the currency.
     */
    private constructor(currency: Int, currencyDesc: ISO4217.CurrencyInfo?,
                        divisor: Int = currencyDesc?.decimalDigits?.let { NumberUtils.pow(10, it).toInt() } ?:
                        DEFAULT_DIVISOR) : this(currency,
            currencyDesc?.symbol ?: UNKNOWN_CURRENCY_CODE,
            divisor)

    /**
     * Tests equality between two [TransitCurrency], using a common denominator if [mDivisor]s
     * differ.
     */
    override fun equals(other: Any?): Boolean {
        if (other !is TransitCurrency)
            return false

        if (mCurrencyCode != other.mCurrencyCode) {
            return false
        }

        if (mDivisor == other.mDivisor) {
            return mCurrency == other.mCurrency
        }

        // Divisors don't match -- coerce to a common denominator
        return mCurrency * other.mDivisor == other.mCurrency * mDivisor
    }

    /**
     * Tests equality between two [TransitCurrency], ensuring identical [mDivisor]s.
     */
    @VisibleForTesting
    fun exactlyEquals(other: Any?): Boolean {
        if (other !is TransitCurrency) {
            return false
        }

        return (mCurrency == other.mCurrency &&
                mCurrencyCode == other.mCurrencyCode &&
                mDivisor == other.mDivisor)
    }

    private fun obfuscate(fareOffset: Int, fareMultiplier: Double): TransitCurrency {
        var cur = ((mCurrency + fareOffset) * fareMultiplier).toInt()

        // Match the sign of the original fare
        if (cur > 0 && mCurrency < 0 || cur < 0 && mCurrency >= 0) {
            cur *= -1
        }

        return TransitCurrency(cur, mCurrencyCode, mDivisor)
    }

    override fun obfuscate(): TransitCurrency =
        obfuscate(TripObfuscator.randomSource.nextInt(100) - 50,
            TripObfuscator.randomSource.nextDouble() * 0.4 + 0.8)

    /**
     * This handles Android-specific issues:
     *
     *
     * - Some currency formatters return too many or too few fractional amounts. (issue #34)
     * - Markup with TtsSpan.MoneyBuilder, for accessibility tools.
     *
     * @param isBalance True if the value being passed is a balance (ie: don't format credits in a
     * special way)
     * @return Formatted currency string
     */
    override fun formatCurrencyString(isBalance: Boolean): FormattedString {
        return formatCurrency(mCurrency, mDivisor, mCurrencyCode, isBalance)
    }

    fun maybeObfuscateBalance(): TransitCurrency {
        return if (!Preferences.obfuscateBalance) {
            this
        } else obfuscate()

    }

    fun maybeObfuscateFare(): TransitCurrency {
        return if (!Preferences.obfuscateTripFares) {
            this
        } else obfuscate()

    }

    fun negate() = TransitCurrency(-mCurrency, mCurrencyCode, mDivisor)

    /**
     * Adds another [TransitCurrency] to this [TransitCurrency].
     *
     * @param other The other [TransitCurrency] to add to this [TransitCurrency].
     * If `null`, this method returns `this`.
     * @throws IllegalArgumentException If the currency code of `this` and [other] are not equal.
     */
    operator fun plus(other: TransitCurrency?) : TransitCurrency {
        return when {
            other == null -> this

            mCurrencyCode != other.mCurrencyCode ->
                throw IllegalArgumentException("Currency codes must be the same")

            mDivisor != other.mDivisor -> when {
                // Divisors don't match! Find a common divisor.
                mDivisor > other.mDivisor && (mDivisor % other.mDivisor == 0) ->
                    // Use mDivisor (> other and divisible)
                    TransitCurrency(mCurrency + (other.mCurrency * (mDivisor / other.mDivisor)),
                            mCurrencyCode, mDivisor)

                other.mDivisor > mDivisor && (other.mDivisor % mDivisor == 0) ->
                    // Use other divisor (> this and divisible)
                    TransitCurrency(other.mCurrency + (mCurrency * (other.mDivisor / mDivisor)),
                            mCurrencyCode, other.mDivisor)

                else ->
                    // Be lazy
                    TransitCurrency((mCurrency * other.mDivisor) + (other.mCurrency * mDivisor),
                            mCurrencyCode, mDivisor * other.mDivisor)
            }

            else ->
                TransitCurrency(mCurrency + other.mCurrency, mCurrencyCode, mDivisor)
        }
    }

    /**
     * String representation of a TransitCurrency.
     *
     * This should only ever be used by debug logs and unit tests. It does not handle any
     * localisation or formatting.
     *
     * @return String representation of the value, eg: "TransitCurrency.AUD(1234)" for AUD 12.34.
     */
    override fun toString() = "$TAG.$mCurrencyCode($mCurrency, $mDivisor)"

    override fun hashCode(): Int {
        var result = mCurrencyCode.hashCode()
        result = 31 * result + mCurrency * 100 / mDivisor
        return result
    }

    @Suppress("FunctionName")
    companion object {
        /**
         * Invalid or no currency information, per ISO 4217.
         */
        internal const val UNKNOWN_CURRENCY_CODE = "XXX"
        private const val DEFAULT_DIVISOR = 100
        private const val TAG = "TransitCurrency"

        fun AUD(cents: Int) = TransitCurrency(cents, "AUD")
        fun BRL(centavos: Int) = TransitCurrency(centavos, "BRL")
        fun CAD(cents: Int) = TransitCurrency(cents, "CAD")
        fun CLP(pesos: Int) = TransitCurrency(pesos, "CLP", 1)
        fun CNY(fen: Int) = TransitCurrency(fen, "CNY")
        fun DKK(ore: Int) = TransitCurrency(ore, "DKK")
        fun EUR(cents: Int) = TransitCurrency(cents, "EUR")
        fun GBP(pence: Int) = TransitCurrency(pence, "GBP")
        fun HKD(cents: Int) = TransitCurrency(cents, "HKD")
        fun IDR(cents: Int) = TransitCurrency(cents, "IDR", 1)
        fun ILS(agorot: Int) = TransitCurrency(agorot, "ILS")
        fun JPY(yen: Int) = TransitCurrency(yen, "JPY", 1)
        fun KRW(won: Int) = TransitCurrency(won, "KRW", 1)
        fun MYR(sen: Int) = TransitCurrency(sen, "MYR")
        fun NZD(cents: Int) = TransitCurrency(cents, "NZD")
        fun RUB(kopeyka: Int) = TransitCurrency(kopeyka, "RUB")
        fun SGD(cents: Int) = TransitCurrency(cents, "SGD")
        fun TWD(cents: Int) = TransitCurrency(cents, "TWD", 1)
        fun USD(cents: Int) = TransitCurrency(cents, "USD")

        /**
         * Constructor for use with unknown currencies.
         */
        fun XXX(cents: Int) = TransitCurrency(cents, UNKNOWN_CURRENCY_CODE)
        fun XXX(cents: Int, divisor: Int) = TransitCurrency(cents, UNKNOWN_CURRENCY_CODE, divisor)
    }
}

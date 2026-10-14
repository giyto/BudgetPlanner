package ru.hznik.budget.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ru.hznik.budget.R
import ru.hznik.budget.domain.model.Category
import ru.hznik.budget.ui.theme.BudgetTheme
import ru.hznik.budget.ui.theme.categoryColors

/** Иконки категорий по ключу из базы, в порядке показа в выборе иконки. */
object CategoryIcons {
    val all: List<Pair<String, Int>> = listOf(
        "cart" to R.drawable.ic_cat_cart,
        "cup" to R.drawable.ic_cat_cup,
        "bus" to R.drawable.ic_cat_bus,
        "car" to R.drawable.ic_cat_car,
        "phone" to R.drawable.ic_cat_phone,
        "film" to R.drawable.ic_cat_film,
        "shirt" to R.drawable.ic_cat_shirt,
        "pill" to R.drawable.ic_cat_pill,
        "repeat" to R.drawable.ic_cat_repeat,
        "home" to R.drawable.ic_cat_home,
        "dumbbell" to R.drawable.ic_cat_dumbbell,
        "book" to R.drawable.ic_cat_book,
        "plane" to R.drawable.ic_cat_plane,
        "paw" to R.drawable.ic_cat_paw,
        "gift" to R.drawable.ic_cat_gift,
        "transfer" to R.drawable.ic_cat_transfer,
        "cap" to R.drawable.ic_cat_cap,
        "briefcase" to R.drawable.ic_cat_briefcase,
        "wallet" to R.drawable.ic_cat_wallet,
        "dots" to R.drawable.ic_cat_dots,
    )
    private val byKey = all.toMap()

    // Названия для TalkBack в выборе иконки; приложение только на русском (раздел 1 ТЗ).
    private val labels = mapOf(
        "cart" to "Корзина", "cup" to "Чашка", "bus" to "Автобус", "car" to "Машина", "phone" to "Телефон",
        "film" to "Кино", "shirt" to "Одежда", "pill" to "Таблетка", "repeat" to "Подписка", "home" to "Дом",
        "dumbbell" to "Спорт", "book" to "Книга", "plane" to "Самолёт", "paw" to "Питомец", "gift" to "Подарок",
        "transfer" to "Перевод", "cap" to "Учёба", "briefcase" to "Работа", "wallet" to "Кошелёк", "dots" to "Другое",
    )

    @DrawableRes
    fun of(key: String): Int = byKey[key] ?: R.drawable.ic_cat_dots

    fun label(key: String): String = labels[key] ?: key
}

/** Плитка категории: иконка её цвета на светлом фоне того же цвета, как в макетах. */
@Composable
fun CategoryTile(
    icon: String,
    color: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val colors = BudgetTheme.colors.categoryColors(color)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .background(colors.container, RoundedCornerShape(size * 0.33f)),
    ) {
        Icon(
            painter = painterResource(CategoryIcons.of(icon)),
            contentDescription = null,
            tint = colors.content,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
fun CategoryTile(category: Category, modifier: Modifier = Modifier, size: Dp = 40.dp) =
    CategoryTile(category.icon, category.color, modifier, size)

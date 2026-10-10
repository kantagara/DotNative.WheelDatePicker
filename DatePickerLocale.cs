using System.Globalization;

namespace DotNative.WheelDatePicker;

internal static class DatePickerLocale
{
    internal static CultureInfo Resolve(string? locale)
    {
        if (locale is null)
            return CultureInfo.CurrentCulture;
        ArgumentException.ThrowIfNullOrWhiteSpace(locale);
        return CultureInfo.GetCultureInfo(locale);
    }

    internal static char[] Order(CultureInfo culture)
    {
        var order = new List<char>(3);
        var pattern = culture.DateTimeFormat.ShortDatePattern;
        var quote = '\0';
        for (var i = 0; i < pattern.Length; i++)
        {
            var part = pattern[i];
            if (part == '\\')
            {
                i++;
                continue;
            }
            if (quote != '\0')
            {
                if (part == quote)
                    quote = '\0';
                continue;
            }
            if (part is '\'' or '"')
            {
                quote = part;
                continue;
            }
            var end = i + 1;
            while (end < pattern.Length && pattern[end] == part)
                end++;
            // A weekday name (ddd/dddd) does not represent the day column.
            if ((part is 'M' or 'y' || part == 'd' && end - i <= 2) && !order.Contains(part))
                order.Add(part);
            i = end - 1;
        }
        foreach (var part in "dMy")
            if (!order.Contains(part))
                order.Add(part);
        return order.ToArray();
    }
}

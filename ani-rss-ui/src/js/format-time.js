const DISPLAY_TIME_ZONE = "Asia/Shanghai";
const DATE_TIME_FORMATTER = new Intl.DateTimeFormat("zh-CN", {
    timeZone: DISPLAY_TIME_ZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false
});

const toDate = timestamp => {
    if (typeof timestamp === "number") {
        return new Date(timestamp);
    }

    if (typeof timestamp === "string" && timestamp.trim().length) {
        const timestampNumber = Number(timestamp);
        if (!Number.isNaN(timestampNumber)) {
            return new Date(timestampNumber);
        }

        return new Date(timestamp);
    }

    return new Date(Number.NaN);
}

const formatDateTime = date => {
    const parts = DATE_TIME_FORMATTER.formatToParts(date)
        .reduce((result, part) => {
            result[part.type] = part.value;
            return result;
        }, {});

    return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
}

let formatTime = timestamp => {
    const target = toDate(timestamp);
    if (Number.isNaN(target.getTime())) {
        return "";
    }

    const now = new Date();
    const elapsedMs = now.getTime() - target.getTime();
    const elapsedMin = Math.floor(elapsedMs / (1000 * 60));

    if (elapsedMin < 1) {
        return "刚刚";
    }

    if (elapsedMin < 60) {
        return `${elapsedMin}分钟前`;
    }

    const hour = Math.floor(elapsedMs / (1000 * 60 * 60));

    if (hour < 24) {
        return `${hour}小时前`;
    }

    const day = Math.floor(elapsedMs / (1000 * 60 * 60 * 24));

    if (day >= 1 && day <= 3) {
        return `${day}天前`;
    }

    const targetDateTime = formatDateTime(target);
    const nowDateTime = formatDateTime(now);

    // 是否为当前年
    const isCurrentYear = targetDateTime.slice(0, 4) === nowDateTime.slice(0, 4);

    return isCurrentYear ? targetDateTime.slice(5) : targetDateTime;
}

export default formatTime;

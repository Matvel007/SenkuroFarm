# Как перейти на Pay SDK

Платежи in-app и подписки PaySDK Как перейти на Pay SDK

# Как перейти на Pay SDK

В принципах функционирования Pay SDK есть отличия отbillingClient SDK.

В этом разделе собран список ключевых изменений по сравнению с billingClient SDK, на

которые важно обратить внимание. Все подробности с примерами кода вы можете найти в

документациидля конкретной версии Pay SDK.

## Список зависимостей

У Pay SDK сократилсясписок зависимостей.

## Подключение в проект

Приподключения зависимостиучитывайте отличие в названии SDK (**pay**вместо

## **billingclient**):

BillingClient SDK:

Pay SDK:

## Инициализация

Изменился способ указания**consoleApplicationId**приинициализации:

BillingClient SDK: для инициализации необходимо создать экземпляр

**RuStoreBillingClient**с помощью метода**RuStoreBillingClientFactory.create()**

в коде вашего приложения и передать туда**consoleApplicationId**.

Pay SDK: в файле AndroidManifest.xml необходимо добавить параметр

**console_app_id_value**. Параметры**themeProvider**,**externalPaymentLoggerFactory**

и**debugLogs**не указываются.

Обработка deeplink

**dependencies {** **implementation(platform("ru.rustore.sdk:bom:2025.02.01"))** **implementation("ru.rustore.sdk:billingclient")** **}**

**dependencies {** **implementation(platform("ru.rustore.sdk:bom:2025.11.01"))** **implementation("ru.rustore.sdk:pay")** **}**

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 1/13

Для указания deeplink-схемы в BillingClient SDK используется метод

## **RustoreBillingClientFactory.create()**.

ВPay SDK для указания deeplink-схемыиспользуется файл**AndroidManifest.xml**, где

указывается**sdk_pay_scheme_value**. Для обработки deeplink добавлен публичный

интерактор**IntentInteractor**.

Обработка deeplink в Activity (Kotlin)

## Проверка доступности работы с платежами

Изменился методпроверки доступностиработы с платежами:

BillingClient SDK:

## **RuStoreBillingClient.Companion.checkPurchasesAvailability()**.

Pay SDK:

## **RuStorePayClient.instance.getPurchaseInteractor().getPurchaseAvailabilit**

## **y()**.

Поменялись ответы метода:

BillingClient SDK:**FeatureAvailabilityResult.Available**и

## **FeatureAvailabilityResult.Unavailable(val cause: RuStoreException)**.

## **class****YourBillingActivity****:****AppCompatActivity****()****{**

**private****val****intentInteractor****:****IntentInteractor****by****lazy****{** **RuStorePayClient****.****instance****.****getIntentInteractor****()** **}**

**override****fun****onCreate****(****savedInstanceState****:****Bundle****?****)****{** **super****.****onCreate****(****savedInstanceState****)**

**if****(****savedInstanceState****==****null****)****{** **// Определение темы платежной шторки (LIGHT или DARK)** **intentInteractor****.****proceedIntent****(****intent****,****sdkTheme****=** **SdkTheme****.****LIGHT****)** **}** **}**

**override****fun****onNewIntent****(****intent****:****Intent****?****)****{** **super****.****onNewIntent****(****intent****)** **// Определение темы платежной шторки (LIGHT или DARK)** **intentInteractor****.****proceedIntent****(****intent****,****sdkTheme****=****SdkTheme****.****LIGHT****)** **}**

## **}**

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 2/13

Pay SDK:**PurchaseAvailabilityResult.Available**и

## **PurchaseAvailabilityResult.Unavailable(val cause: Throwable)**.

## Проверка статуса авторизации пользователя

Для проверки авторизации пользователя в Pay SDK используется новый метод

## **UserInteractor**:

## Получение списка продуктов

В Pay SDKполучение списка продуктовне требует авторизации пользователя.

Теперь можно запрашивать до 1000 элементов за один запрос, в то время как в

BillingClient SDK — до 100.

Изменился метод получения списка продуктов:

BillingClient SDK:**billingClient.products productsUseCase.getProducts()**.

Pay SDK:**RuStorePayClient.getProductInteractor().getProducts(productsId:**

## **List<ProductId>)**.

Структура модели Product

В возвращаемой модели продукта изменилась структура. В таблице ниже приведены

соответствия полей, возвращаемых обоими SDK. Подробное описание полей см. в

документацииbillingClient SDKиPay SDK.

BillingClient SDK Pay SDK

## **productId** **productId**

**RuStorePayClient****.****instance****.****getUserInteractor****().****getUserAuthorizationStatus****()** **.****addOnSuccessListener****{****result****->** **when****(****result****)****{** **UserAuthorizationStatus****.****AUTHORIZED****->****{** **// Пользователь авторизован в RuStore или через VK ID на** **платежной шторке** **}** **UserAuthorizationStatus****.****UNAUTHORIZED****->****{** **// Пользователь не авторизован** **}** **}** **}** **.****addOnFailureListener****{****throwable****->** **// Обработка ошибки** **}**

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 3/13

BillingClient SDK Pay SDK

## **productType** **type**

## **productStatus** —

## **priceLabel** **amountLabel**

## **price** **price**

## **currency** **currency**

## **language** —

## **title** **title**

## **description** **description**

## **imageUrl** **imageUrl**

## **promoImageUrl** —

## **subscription** **subscriptionInfo**

Поле subscriptionInfo

Для продуктов типа**SUBSCRIPTION**теперь доступна детальная информация о подписке в

поле**subscriptionInfo**:

Типы периодов:

## **public****class****SubscriptionInfo****{**

## **public****val****periods****:****List****<****SubscriptionPeriod****>** **}**

**public****class****TrialPeriod****(** **public****val****duration****:****String****,** **public****val****currency****:****String****,** **public****val****price****:****Int** **)****:****SubscriptionPeriod**

**public****class****PromoPeriod****(** **public****val****duration****:****String****,** **public****val****currency****:****String****,** **public****val****price****:****Int** **)****:****SubscriptionPeriod**

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 4/13

Пример работы с**subscriptionInfo**:

**public****class****MainPeriod****(** **public****val****duration****:****String****,** **public****val****currency****:****String****,** **public****val****price****:****Int** **)****:****SubscriptionPeriod**

**public****class****GracePeriod****(** **public****val****duration****:****String** **)****:****SubscriptionPeriod**

**public****class****HoldPeriod****(** **public****val****duration****:****String** **)****:****SubscriptionPeriod**

**RuStorePayClient****.****instance****.****getProductInteractor****().****getProducts****(** **productsId****=****listOf****(****ProductId****("subscription_id"))** **)** **.****addOnSuccessListener****{****products****:****List****<****Product****>****->** **products****.****forEach****{****product****->** **product****.****subscriptionInfo****?****.****periods****?****.****forEach****{****period****->** **when****(****period****)****{** **is****TrialPeriod****->****{** **println****("Trial период: ${****period****.****duration****} за** **${****period****.****price****} ${****period****.****currency****}")** **}** **is****PromoPeriod****->****{** **println****("Promo период: ${****period****.****duration****} за** **${****period****.****price****} ${****period****.****currency****}")** **}** **is****MainPeriod****->****{** **println****("Main период: ${****period****.****duration****} за** **${****period****.****price****} ${****period****.****currency****}")** **}** **is****GracePeriod****->****{** **println****("Grace период: ${****period****.****duration****}")** **}** **is****HoldPeriod****->****{**

**println****("Hold ожидания: ${****period****.****duration****}")** **}** **}** **}** **}** **}**

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 5/13

## Получение списка покупок

Изменился методполучения списка покупок:

BillingClient SDK:**billingClient.purchases purchasesUseCase.getPurchases()**.

Pay SDK:**RuStorePayClient.getPurchaseInteractor().getPurchases()**.

ОСОБЕННОСТИ МИГРАЦИИ ПОКУПОК И ПОДПИСОК

Разовые товары, купленные через BillingClient SDK, после перехода доступны в Pay SDK.

Подписки автоматически не переносятся. Поэтому на время миграции рекомендуется

использовать оба SDK: оформлять все новые подписки через Pay SDK, а для

пользователей со старыми подписками продолжать получать их статус через BillingClient

SDK и на его основе предоставлять доступ.

Метод поддерживает фильтрацию:

по типу товара (**productType**): потребляемые, непотребляемые товары, подписки;

по статусу покупки (**purchaseStatus**):

для товаров:**PAID**,**CONFIRMED**.

для подписок:**ACTIVE**,**PAUSED**.

## Типы покупок

Изменилась структура ответа методов получения сведений о покупке и списка покупок,

появился общий интерфейс**Purchase**и две его реализации:

## **ProductPurchase**для разовых покупок.

## **SubscriptionPurchase**для подписок.

Разделение сделано для того, чтобы сгруппировать общую логику и данные, но при этом

позволить каждому типу покупки иметь свои уникальные свойства и поведение.

В следующей таблице приведены списки полей, которые возвращают оба SDK.

BillingClient SDK

Pay SDK (общий

интерфейс)

Pay SDK

ProductPurchase

Pay SDK

SubscriptionPurchase

## **purchaseId** **purchaseId** **purchaseId** **purchaseId**

## **productId** — **productId** **productId**

## **invoiceId** **invoiceId** **invoiceId** **invoiceId**

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 6/13

BillingClient SDK

Pay SDK (общий

интерфейс)

Pay SDK

ProductPurchase

Pay SDK

SubscriptionPurchase

## **language** — — —

## **purchaseTime** **purchaseTime** **purchaseTime** **purchaseTime**

## **orderId** **orderId** **orderId** **orderId**

— **purchaseType** **purchaseType** **purchaseType**

— **description** **description** **description**

## **amountLabel** **amountLabel** **amountLabel** **amountLabel**

## **amount** **price** **price** **price**

## **currency** **currency** **currency** **currency**

## **quantity** — **quantity** —

— — **productType** -

## **purchaseState** **status** **status** **status**

**developerPayload** **developerPayload** **developerPayload** **developerPayload**

## **subscriptionToken** — — —

## **sandbox** **sandbox** **sandbox** **sandbox**

— — — **expirationDate**

— — — **gracePeriodEnabled**

## Получение сведений о покупке

Изменился методполучения сведений о покупке:

BillingClient SDK:**billingClient.purchases**

## **purchasesUseCase.getPurchaseInfo(PurchaseId("purchaseId"))**.

Pay SDK:

## **RuStorePayClient.getPurchaseInteractor().getPurchase(PurchaseId("purchas**

## **eId"))**.

## Статусы покупки

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 7/13

BillingClient SDK Pay SDK ProductPurchase Pay SDK SubscriptionPurchase

## **CREATED** **-** **-**

## **INVOICE_CREATED** **INVOICE_CREATED** **INVOICE_CREATED**

## **CANCELLED** **CANCELLED** **CANCELLED**


- **PROCESSING** **PROCESSING**



- **REJECTED** **REJECTED**


## **CONFIRMED** **CONFIRMED** **-**

## **CONSUMED** **-** **-**


- **REFUNDED** **-**



- **REFUNDING** **-**



- **EXECUTING** **-**



- **EXPIRED** **EXPIRED**


## **PAID** **PAID** **-**


- **REVERSED** **-**



- - **ACTIVE**


## **PAUSED** - **PAUSED**

## **TERMINATED** - **TERMINATED**


- - **CLOSED**


## Покупка продукта

Методпокупки продуктазаменен на два новых метода:

BillingClient SDK:**billingClient.purchases**

## **purchasesUseCase.purchaseProduct()**.

Pay SDK:

**RuStorePayClient****.****instance****.****getPurchaseInteractor****().****purchase****(** **params****:****ProductPurchaseParams****,** **preferredPurchaseType****:****PreferredPurchaseType****=**

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 8/13

В**billingClient SDK**стадийность платежа была привязана к типу продукта

(потребляемый/непотребялемый), в Pay SDK вы сами определяете стадийность

платежа при запуске оплаты. Универсальный метод для запуска покупки. Позволяет

выбрать тип оплаты — одностадийную или двухстадийную.

Параметры**ProductPurchaseParams**:

## **productId**— идентификатор продукта;

## **quantity**— количество (опционально);

## **orderId**— ID заказа (опционально);

## **developerPayload**— доп. информация (опционально);

## **appUserId**— внутренний ID пользователя (опционально);

## **appUserEmail**— email пользователя для автозаполнения чека (опционально).

Типы оплаты:

## **ONE_STEP**(по умолчанию) — средства списываются сразу;

## **TWO_STEP**— выполняется попытка двухстадийной оплаты (холдирование средств).

Двухстадийная оплата (с холдированием средств)

Для гарантированной двухстадийной оплаты используйте метод**purchaseTwoStep()**:

На платежной шторке будут доступны только способы оплаты, поддерживающие

холдирование.

Важно:Для подписок доступна только одностадийная оплата (**ONE_STEP**).

Параметр sdkTheme

Все платежные методы в Pay SDK теперь поддерживают выбор темы платежной шторки:

**PreferredPurchaseType****.****ONE_STEP****,** **sdkTheme****:****SdkTheme****=****SdkTheme****.****LIGHT****//Определение темы платежной** **шторки (LIGHT или DARK)** **)**

**RuStorePayClient****.****instance****.****getPurchaseInteractor****().****purchaseTwoStep****(**

**params****:****ProductPurchaseParams****,** **sdkTheme****:****SdkTheme****=****SdkTheme****.****LIGHT****//Определение темы платежной** **шторки (LIGHT или DARK)** **)**

**// Светлая тема (по умолчанию)** **purchase****(****params****,****preferredPurchaseType****,****sdkTheme****=****SdkTheme****.****LIGHT****)**

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 9/13

Изменились ответы метода:

BillingClient SDK:**Success**,**Failure**,**Cancelled**и**InvalidPaymentState**.

Pay SDK: для успешной покупки результат возвращается в виде класса

## **ProductPurchaseResult**. Отдельные классы отмены и ошибки больше не

используются. Для обработки ошибок используется**OnFailureListener**, в котором

указывается поведение при возникновении

## **RustorePaymentException.ProductPurchaseException**(общая ошибка) и

## **RustorePaymentException.ProductPurchaseCancelled**(отмена пользователем).

В Pay SDK для методов покупки добавлены необязательные параметры**appUserId**и

## **appUserEmail**.

## Обработка ошибок оплаты

Если в процессе оплаты возникает ошибка или пользователь отменяет покупку, выполнение

метода оплаты (как с выбором типа покупки, так и двухстадийного метода) завершается с

ошибкой.

Для обработки ошибок используется**OnFailureListener**, внутри которого определяется

тип ошибки и указывается поведение при ее возникновении:

## **ProductPurchaseException**— ошибка покупки продукта.

## **ProductPurchaseCancelled**— ошибка, вызванная отменой покупки продукта

(пользователь закрыл платежную шторку) до получения результата покупки. В таком

случае рекомендуется дополнительно проверить статус покупки методом получения

информации о покупке.

Более подробная информация и примеры кода приведенына странице Pay SDK

## Серверная валидация

Длясерверной валидацииразовых покупок используется:

BillingClient SDK:**subscriptionToken**, который можно получить при успешной покупке

продукта из**PaymentResult.Success**.

Pay SDK:**invoiceId**(идентификатор счета) используется для серверной валидации

платежей,**purchase id**— для получения информации по подписке. См.API: Получение

**// Темная тема** **purchase****(****params****,****preferredPurchaseType****,****sdkTheme****=****SdkTheme****.****DARK****)**

Обработка ошибок оплаты в версиях Pay SDK до 8.0.0

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 10/13

данных о платеже по его идентификатору (v2),Валидация подписок,Подтверждение

получения подписок.

## Подтверждение покупки

Изменился методподтверждения покупки:

BillingClient SDK:**billingClient.purchases purchasesUseCase.confirmPurchase()**.

Pay SDK:**RuStorePayClient.getPurchaseInteractor().confirmTwoStepPurchase()**

— подтверждение покупки при двухстадийной оплате.

## Отмена покупки

Изменился методотмены покупки:

BillingClient SDK:**billingClient.purchases purchasesUseCase.deletePurchase()**

Pay SDK:**RuStorePayClient.getPurchaseInteractor().cancelTwoStepPurchase()**

— отмена покупки при двухстадийной оплате.

## См. также

Описание Pay SDK

Pay SDK Kotlin/Java

Pay SDK React-Native

Pay SDK Godot

Pay SDK Unity

Pay SDK Defold

Pay SDK Unreal

Pay SDK Flutter

## История изменений

Версия SDK 10.1.0

Добавлена темная тема (цветовая тема оформления платежной шторки)

Расширена модель**Product**для работы с подписками (поле**subscriptionInfo**и

классы**SubscriptionInfo**и**SubscriptionPeriod**)

Реализован механизм авторизации через VK ID на шторке оплаты. При попытке купить

подписку будучи неавторизированным, появляется экран авторизации. При покупке in-app

товара путь пользователя не меняется. Окно с авторизацией не отображается.

Pay SDK 10.0.0

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 11/13

Добавлена возможность покупки подписок в SDK.

Добавлены новые статусы покупок (для подписок) и тип покупок для фильтрации списка

покупок.

Добавлен новый метод оплаты SberPay.

Добавлена подпись к лоадеру на экранах проверки статуса покупки.

Публичная модель PurchaseStatus заменена на ProductPurchaseStatus и

SubscriptionPurchaseStatus, представляющие разные статусные модели продуктов.

Удален статус ProductPurchaseStatus.CONSUMED и добавлен

ProductPurchaseStatus.REFUNDING.

Добавлено новое поле productType: ProductType в модель ответа успешной покупки, в

модели ошибки и отмены покупки продукта.

Подключена новая зависимость в виде Tracer Light.

Исправлена критическая ошибка при отмене покупки.

Pay SDK 9.0.1

Добавлена оплата вне RuStore.

Добавлена оплата и сохранение карты VK ID.

Добавлена функция создания и применения купонов.

Pay SDK 8.0.0

Метод одностадийной оплаты**purchaseOneStep**заменен универсальным методом

**purchase**, который позволяет указать тип оплаты (одностадийная или двухстадийная).

Двухстадийная оплата (**TWO_STEP**) теперь доступна только для ограниченного набора

способов оплаты.

Улучшен метод**purchaseTwoStep**, который теперь обеспечивает гарантированную

двухстадийную оплату.

Добавлена ошибка**RuStorePayInvalidActivePurchase**при попытке оплаты продукта

неизвестного типа.

Добавлена возможность проводить тестовые платежи (sandbox).

Pay SDK 7.0.0

Единый метод покупки заменен на два новых метода для одностадийной и двухстадийной

оплате.

Вместо статусных моделей потребляемых или непотребляемых продуктов теперь

используются статусные модели покупки по одностадийной и двухстадийной оплате.

Статус**CONSUMED**заменен на**CONFIRMED**.

Метод подтверждения покупки**consumePurchase**заменен на**confirmTwoStepPurchase**

для двухстадийной оплаты.

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 12/13

Появился метод отмены покупки при двухстадийной оплате.

Pay SDK 6.1.0

Первая версия инструкции по переходу с BillingClient SDK на Pay SDK 6.1.0.

26.06.2026, 01:21 Как перейти на Pay SDK

https://www.rustore.ru/help/sdk/pay/migration 13/13

